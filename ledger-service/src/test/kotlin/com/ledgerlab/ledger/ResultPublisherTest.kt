package com.ledgerlab.ledger

import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.ledger.service.LedgerService
import com.ledgerlab.ledger.service.RunService
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.ConsumerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.Properties
import java.util.UUID

class ResultPublisherTest : IntegrationTest() {

    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var runs: RunService
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var consumerFactory: ConsumerFactory<String, String>
    @Autowired lateinit var objectMapper: ObjectMapper
    @Value("\${ledger.results.topic}") lateinit var resultsTopic: String

    @Test
    fun `publishes one result event per processed transfer and marks the row published`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 3, openingBalanceCents = 1_000, blockedAccounts = 1))
        val posted = transfer(runId, from = 1, to = 2, amountCents = 100)
        val rejected = transfer(runId, from = 1, to = 3, amountCents = 100)
        val received = mutableListOf<TransferResultEvent>()

        val overrides = Properties().apply { put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") }
        consumerFactory.createConsumer("results-test-${UUID.randomUUID()}", null, null, overrides).use { consumer ->
            consumer.subscribe(listOf(resultsTopic))
            ledger.apply(posted)
            ledger.apply(rejected)
            ledger.apply(posted) // duplicate delivery must not produce a second event

            await().atMost(Duration.ofSeconds(30)).untilAsserted {
                consumer.poll(Duration.ofMillis(200)).forEach {
                    received += objectMapper.readValue(it.value(), TransferResultEvent::class.java)
                }
                assertThat(received.filter { it.runId == runId }).hasSize(2)
            }
        }

        val byTransfer = received.filter { it.runId == runId }.associateBy { it.transferId }
        assertThat(byTransfer.getValue(posted.transferId).outcome).isEqualTo(TransferOutcome.POSTED)
        assertThat(byTransfer.getValue(rejected.transferId).outcome).isEqualTo(TransferOutcome.REJECTED)
        assertThat(byTransfer.getValue(rejected.transferId).rejectReason).isEqualTo("BLOCKED_ACCOUNT")
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val unpublished = jdbc.sql("SELECT count(*) FROM transfers WHERE run_id = :r AND result_published_at IS NULL")
                .param("r", runId).query(Long::class.java).single()
            assertThat(unpublished).isZero()
        }
    }
}
