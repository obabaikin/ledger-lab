package com.ledgerlab.ledger

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.metrics.RunDuplicates
import com.ledgerlab.ledger.service.RunService
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

@AutoConfigureTestRestTemplate
class TransferConsumerTest : IntegrationTest() {

    @Autowired lateinit var http: TestRestTemplate

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var runs: RunService
    @Autowired lateinit var registry: MeterRegistry
    @Value("\${ledger.topic.name}") lateinit var topic: String

    @Test
    fun `consumes transfers from kafka, applies each exactly once and reconciles`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 4, openingBalanceCents = 1_000))
        val commands = (1..40).map { i -> transfer(runId, from = i % 4 + 1, to = (i + 1) % 4 + 1, amountCents = 10) }

        commands.forEach { send(it) }
        commands.take(10).forEach { send(it) }

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertThat(runs.reconcile(runId).postedTransfers).isEqualTo(40)
            assertThat(duplicateCount(runId)).isEqualTo(10.0)
        }
        val report = runs.reconcile(runId)
        assertThat(report.balanced).isTrue()
        assertThat(report.balanceTotalCents).isEqualTo(4_000)
        // The names control queries in Prometheus (ADR 0015).
        assertThat(http.getForObject("/actuator/prometheus", String::class.java))
            .contains("ledger_duplicates_total{run=\"$runId\"} 10.0")
            .containsPattern("(?m)^ledger_kafka_assigned_partitions [1-9]")
    }

    private fun send(command: TransferCommand) {
        kafka.send(topic, command.partitionKey(), objectMapper.writeValueAsString(command)).get()
    }

    private fun duplicateCount(runId: UUID): Double =
        registry.find(RunDuplicates.METRIC).tag(RunDuplicates.RUN_TAG, runId.toString()).counter()?.count() ?: 0.0
}
