package com.ledgerlab.reporting

import com.ledgerlab.common.RejectReason
import com.ledgerlab.common.TransferCommand
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.reporting.persistence.RunStatsRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ProjectionTest : IntegrationTest() {

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var stats: RunStatsRepository
    @Autowired lateinit var props: ReportingProperties

    @Test
    fun `commands and results from both topics, some delivered twice and results first, add up once`() {
        val runId = UUID.randomUUID()
        val now = Instant.now()
        val commands = (1..30).map { command(runId, 10, now) }
        val results = commands.mapIndexed { i, c ->
            if (i % 3 == 0) rejected(c, RejectReason.DAILY_LIMIT_EXCEEDED, now.plusMillis(50)) else posted(c, now.plusMillis(50))
        }

        results.take(10).forEach { send(it) }
        commands.forEach { send(it) }
        commands.take(5).forEach { send(it) }
        results.drop(10).forEach { send(it) }
        results.take(5).forEach { send(it) }

        await().atMost(TIMEOUT).untilAsserted {
            val totals = stats.snapshot(runId).totals
            assertThat(totals.accepted).isEqualTo(30)
            assertThat(totals.posted).isEqualTo(20)
            assertThat(totals.rejected).isEqualTo(10)
            assertThat(totals.inFlight).isZero()
            assertThat(totals.postedAmountCents).isEqualTo(200)
            assertThat(totals.rejectedByReason).containsOnlyKeys(RejectReason.DAILY_LIMIT_EXCEEDED.name)
        }
    }

    @Test
    fun `a reject reason this service does not know is kept as sent`() {
        val runId = UUID.randomUUID()
        val command = command(runId, 10, Instant.now())
        val event = objectMapper.valueToTree<ObjectNode>(rejected(command, RejectReason.BLOCKED_ACCOUNT, Instant.now()))
            .put(TransferResultEvent::rejectReason.name, UNKNOWN_REASON)

        send(command)
        kafka.send(props.resultsTopic, command.transferId.toString(), objectMapper.writeValueAsString(event)).get()

        await().atMost(TIMEOUT).untilAsserted {
            assertThat(stats.snapshot(runId).totals.rejectedByReason).containsEntry(UNKNOWN_REASON, 1L)
        }
    }

    @Test
    fun `an unreadable event is skipped and the ones after it are stored`() {
        val runId = UUID.randomUUID()
        val command = command(runId, 10, Instant.now())

        kafka.send(props.transfersTopic, command.partitionKey(), "not json").get()
        send(command)

        await().atMost(TIMEOUT).untilAsserted { assertThat(stats.snapshot(runId).totals.accepted).isEqualTo(1) }
    }

    private fun send(command: TransferCommand) {
        kafka.send(props.transfersTopic, command.partitionKey(), objectMapper.writeValueAsString(command)).get()
    }

    private fun send(result: TransferResultEvent) {
        kafka.send(props.resultsTopic, result.partitionKey(), objectMapper.writeValueAsString(result)).get()
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        const val UNKNOWN_REASON = "ACCOUNT_FROZEN"
    }
}
