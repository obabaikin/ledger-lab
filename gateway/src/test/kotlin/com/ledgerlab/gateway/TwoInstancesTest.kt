package com.ledgerlab.gateway

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.gateway.service.ResultWaiters
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.test.context.TestPropertySource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Two gateway instances on one database and one broker, as during a deploy, when the new pod starts before the old
 * one stops: this test's context is the first, a second application started beside it on the same containers is the other.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// One recorder per instance on the two result partitions, so each instance records one of them.
@TestPropertySource(properties = ["spring.kafka.listener.concurrency=$RECORDERS_PER_INSTANCE"])
class TwoInstancesTest : IntegrationTest() {

    @Autowired lateinit var postgres: PostgreSQLContainer
    @Autowired lateinit var broker: KafkaContainer
    @Autowired lateinit var registry: KafkaListenerEndpointRegistry
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var consumerFactory: ConsumerFactory<String, String>
    @Autowired lateinit var waiters: ResultWaiters

    private lateinit var other: ConfigurableApplicationContext
    private val otherUrl get() = urlOf(other)

    private fun urlOf(instance: ConfigurableApplicationContext) = "http://localhost:${instance.environment.getProperty("local.server.port")}"

    private fun startInstance(vararg listeners: ApplicationListener<*>): ConfigurableApplicationContext =
        SpringApplicationBuilder(GatewayApplication::class.java)
            .profiles("test")
            .listeners(*listeners)
            // Arguments, not default properties: those rank below application.yaml.
            .run(
                "--server.port=0",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.kafka.bootstrap-servers=${broker.bootstrapServers}",
                "--spring.kafka.listener.concurrency=$RECORDERS_PER_INSTANCE",
            )

    private fun awaitEachOwnsItsShare() {
        await().atMost(Duration.ofSeconds(60)).until {
            listOf(registry, other.getBean(KafkaListenerEndpointRegistry::class.java)).all { it.ownsItsShare() }
        }
    }

    @BeforeAll
    fun startTheOtherInstance() {
        other = startInstance()
        awaitEachOwnsItsShare()
    }

    @AfterAll
    fun stopTheOtherInstance() {
        other.close()
    }

    @Test
    fun `the same transfer sent to two gateway instances is accepted once and both answer it the same`() {
        Executors.newFixedThreadPool(8).use { pool ->
            repeat(10) {
                val request = body()
                val gate = CountDownLatch(1)
                val responses = List(8) { i ->
                    val base = if (i % 2 == 0) "" else otherUrl
                    pool.submit<ResponseEntity<Map<*, *>>> { gate.await(); http.postForEntity("$base/v1/transfers", request, Map::class.java) }
                }
                gate.countDown()

                val answers = responses.map { it.get() }
                assertThat(answers).allSatisfy { assertThat(it.statusCode.is2xxSuccessful).isTrue() }
                assertThat(answers.map { it.body!!["acceptedAt"] }.distinct()).hasSize(1)
                val rows = jdbc.queryForObject("SELECT count(*) FROM transfers WHERE transfer_id = ?", Int::class.java, request["transferId"])
                assertThat(rows).isEqualTo(1)
            }
        }
    }

    @Test
    fun `a waiting request on one instance gets the result the other instance recorded`() {
        val runId = UUID.randomUUID()
        val transferIds = List(TRANSFERS) { UUID.randomUUID() }
        Executors.newFixedThreadPool(TRANSFERS).use { pool ->
            val pending = transferIds.map { id ->
                pool.submit<Pair<ResponseEntity<Map<*, *>>, Long>> { submit(body(id, runId), wait = "PT5S") to System.nanoTime() }
            }
            await().atMost(Duration.ofSeconds(10)).until { waiters.size() == TRANSFERS }
            transferIds.forEach { publishResult(runId, it, TransferOutcome.POSTED) }
            val published = System.nanoTime()

            val answers = pending.map { it.get() }
            assertThat(answers).allSatisfy { (answer, _) ->
                assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(answer.body!!["status"]).isEqualTo(TransferStatus.POSTED.name)
            }
            // A request nobody wakes still reads the row when its wait ends; only the time tells the two apart.
            val slowest = Duration.ofNanos(answers.maxOf { (_, answeredAt) -> answeredAt } - published)
            assertThat(slowest).isLessThan(props.maxWait.dividedBy(2))
        }
    }

    @Test
    fun `with two publishers every transfer reaches Kafka once`() {
        val runId = UUID.randomUUID()
        val transferIds = List(PUBLISHED) { UUID.randomUUID() }
        val overrides = Properties().apply { put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") }
        consumerFactory.createConsumer("two-instances-${UUID.randomUUID()}", null, null, overrides).use { consumer ->
            consumer.subscribe(listOf(props.transfersTopic))
            Executors.newFixedThreadPool(8).use { pool ->
                transferIds.mapIndexed { i, id ->
                    val base = if (i % 2 == 0) "" else otherUrl
                    pool.submit { http.postForEntity("$base/v1/transfers", body(id, runId), Map::class.java) }
                }.forEach { it.get() }
            }

            val published = mutableListOf<UUID>()
            await().atMost(Duration.ofSeconds(30)).untilAsserted {
                consumer.poll(Duration.ofMillis(200)).forEach { published += objectMapper.readValue(it.value(), TransferCommand::class.java).transferId }
                assertThat(published.filter { it in transferIds }).containsAll(transferIds)
            }
            consumer.poll(Duration.ofSeconds(1)).forEach { published += objectMapper.readValue(it.value(), TransferCommand::class.java).transferId }
            assertThat(published.filter { it in transferIds }).doesNotHaveDuplicates()
        }
    }

    @Test
    fun `a request waiting on an instance that is stopping is still woken by its result`() {
        val closing = CountDownLatch(1)
        val stopping = startInstance(ApplicationListener<ContextClosedEvent> { closing.countDown() })
        val stoppingWaiters = stopping.getBean(ResultWaiters::class.java)
        await().atMost(Duration.ofSeconds(60)).until {
            stopping.getBean(KafkaListenerEndpointRegistry::class.java).allListenerContainers
                .single { it.groupId != RECORDER_GROUP }.assignedPartitions.isNullOrEmpty().not()
        }
        val runId = UUID.randomUUID()
        val transferId = UUID.randomUUID()
        Executors.newFixedThreadPool(2).use { pool ->
            val pending = pool.submit<Pair<ResponseEntity<Map<*, *>>, Long>> {
                http.postForEntity("${urlOf(stopping)}/v1/transfers?wait=PT5S", body(transferId, runId), Map::class.java) to System.nanoTime()
            }
            await().atMost(Duration.ofSeconds(10)).until { stoppingWaiters.size() == 1 }
            pool.submit { stopping.close() }
            closing.await()
            // Long enough for listeners that stop first to be gone, short of the drain it is waiting in.
            Thread.sleep(SHUTDOWN_UNDER_WAY.toMillis())
            publishResult(runId, transferId, TransferOutcome.POSTED)
            val published = System.nanoTime()

            val (answer, answeredAt) = pending.get()
            assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(answer.body!!["status"]).isEqualTo(TransferStatus.POSTED.name)
            assertThat(Duration.ofNanos(answeredAt - published)).isLessThan(props.maxWait.dividedBy(2))
        }
        awaitEachOwnsItsShare()
    }

    private fun KafkaListenerEndpointRegistry.ownsItsShare(): Boolean {
        val containers = allListenerContainers
        val recorder = containers.single { it.groupId == RECORDER_GROUP }
        val notifier = containers.single { it.groupId != RECORDER_GROUP }
        return recorder.assignedPartitions?.size == RESULT_PARTITIONS / 2 && !notifier.assignedPartitions.isNullOrEmpty()
    }

    private companion object {
        const val RECORDER_GROUP = "gateway"
        const val RESULT_PARTITIONS = 2
        const val TRANSFERS = 20
        const val PUBLISHED = 400
        val SHUTDOWN_UNDER_WAY: Duration = Duration.ofSeconds(1)
    }
}

private const val RECORDERS_PER_INSTANCE = 1
