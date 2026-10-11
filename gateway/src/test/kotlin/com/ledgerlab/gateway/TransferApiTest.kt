package com.ledgerlab.gateway

import com.ledgerlab.common.TransferCommand
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.ConsumerFactory
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TransferApiTest : IntegrationTest() {

    @Autowired lateinit var consumerFactory: ConsumerFactory<String, String>
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `accepts a transfer with 202 and publishes it to kafka through the outbox`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val overrides = Properties().apply { put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") }
        consumerFactory.createConsumer("api-test-${UUID.randomUUID()}", null, null, overrides).use { consumer ->
            consumer.subscribe(listOf(props.transfersTopic))

            val response = submit(body(transferId, runId, from = 3, to = 7, amount = 250))

            assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
            assertThat(response.body!!["status"]).isEqualTo("ACCEPTED")
            val published = mutableListOf<TransferCommand>()
            await().atMost(Duration.ofSeconds(30)).untilAsserted {
                consumer.poll(Duration.ofMillis(200)).forEach { published += objectMapper.readValue(it.value(), TransferCommand::class.java) }
                assertThat(published.map { it.transferId }).contains(transferId)
            }
            val command = published.first { it.transferId == transferId }
            assertThat(command).usingRecursiveComparison().ignoringFields("createdAt")
                .isEqualTo(TransferCommand(runId, transferId, 3, 7, 250, command.createdAt))
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!["status"]).isEqualTo("PUBLISHED")
        }
    }

    @Test
    fun `a repeated submission with the same id returns the existing transfer`() {
        val request = body()

        val first = submit(request)
        val second = submit(request)

        assertThat(first.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(second.statusCode).isIn(HttpStatus.ACCEPTED, HttpStatus.OK)
        assertThat(second.body!!["transferId"]).isEqualTo(first.body!!["transferId"])
        assertThat(second.body!!["acceptedAt"]).isEqualTo(first.body!!["acceptedAt"])
    }

    @Test
    fun `submissions of one id racing on the insert all get the one transfer`() {
        Executors.newFixedThreadPool(8).use { pool ->
            repeat(10) {
                val request = body()
                val gate = CountDownLatch(1)
                val responses = List(8) { pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { gate.await(); submit(request) } }
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
    fun `the same id with a different payload is rejected with 422`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        submit(body(transferId, runId, amount = 100))

        val conflict = submit(body(transferId, runId, amount = 999))

        assertThat(conflict.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `only version 1 of the public api exists and internal paths are unversioned`() {
        val unknownVersion = http.postForEntity("/v2/transfers", body(), String::class.java)
        assertThat(unknownVersion.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val unversioned = http.postForEntity("/transfers", body(), String::class.java)
        assertThat(unversioned.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val internal = http.getForEntity("/internal/runs/${UUID.randomUUID()}/summary", Map::class.java)
        assertThat(internal.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `validates the request and answers 404 for unknown transfers`() {
        val invalid = submit(body(amount = 0))
        assertThat(invalid.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val unknown = http.getForEntity("/v1/transfers/${UUID.randomUUID()}", String::class.java)
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
