package com.ledgerlab.reporting

import com.ledgerlab.common.RejectReason
import com.ledgerlab.common.TransferCommand
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.reporting.domain.RunStats
import com.ledgerlab.reporting.persistence.AcceptedTransfer
import com.ledgerlab.reporting.persistence.RunStatsRepository
import com.ledgerlab.reporting.persistence.TransferOutcomeRow
import com.ledgerlab.reporting.persistence.TransferStatsRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

@AutoConfigureTestRestTemplate
class RunStatsTest : IntegrationTest() {

    @Autowired lateinit var transfers: TransferStatsRepository
    @Autowired lateinit var stats: RunStatsRepository
    @Autowired lateinit var http: TestRestTemplate

    private val t0 = Instant.parse("2026-10-13T10:00:00Z")

    @Test
    fun `an unknown run has zeros and an empty series`() {
        val snapshot = stats.snapshot(UUID.randomUUID())

        assertThat(snapshot.totals.accepted).isZero()
        assertThat(snapshot.totals.inFlight).isZero()
        assertThat(snapshot.totals.rejectedByReason).isEmpty()
        assertThat(snapshot.bankLatency.p50Ms).isNull()
        assertThat(snapshot.series).isEmpty()
    }

    @Test
    fun `totals count accepted, posted, rejected by reason and the posted amount`() {
        val runId = UUID.randomUUID()
        val a = command(runId, 100, t0)
        val b = command(runId, 200, t0)
        val c = command(runId, 300, t0)
        val d = command(runId, 400, t0)
        store(a, b, c, d)
        store(posted(a, t0.plusMillis(10)), posted(b, t0.plusMillis(20)), rejected(c, RejectReason.INSUFFICIENT_FUNDS, t0.plusMillis(30)))

        val totals = stats.snapshot(runId).totals

        assertThat(totals.accepted).isEqualTo(4)
        assertThat(totals.posted).isEqualTo(2)
        assertThat(totals.rejected).isEqualTo(1)
        assertThat(totals.inFlight).isEqualTo(1)
        assertThat(totals.postedAmountCents).isEqualTo(300)
        assertThat(totals.rejectedByReason).containsExactly(entry(RejectReason.INSUFFICIENT_FUNDS.name, 1))
    }

    @Test
    fun `the series counts acceptance by acceptedAt and outcomes by processedAt`() {
        val runId = UUID.randomUUID()
        val a = command(runId, 100, t0.plusMillis(100))
        val b = command(runId, 200, t0.plusMillis(900))
        store(a, b)
        store(posted(a, t0.plusMillis(1_200)), rejected(b, RejectReason.BLOCKED_ACCOUNT, t0.plusMillis(2_500)))

        val series = stats.snapshot(runId).series

        assertThat(series.map { it.second }).containsExactly(t0, t0.plusSeconds(1), t0.plusSeconds(2))
        assertThat(series.map { it.accepted }).containsExactly(2L, 0L, 0L)
        assertThat(series.map { it.posted }).containsExactly(0L, 1L, 0L)
        assertThat(series.map { it.rejected }).containsExactly(0L, 0L, 1L)
        assertThat(series.map { it.postedAmountCents }).containsExactly(0L, 100L, 0L)
    }

    @Test
    fun `a result seen before its command is accepted in the second of the result, so in flight never goes below zero`() {
        val runId = UUID.randomUUID()
        val early = command(runId, 100, t0.plusMillis(500))
        val late = command(runId, 200, t0.plusMillis(600))
        store(early)
        store(posted(early, t0.plusMillis(1_100)), posted(late, t0.plusMillis(2_100)))

        val snapshot = stats.snapshot(runId)

        assertThat(snapshot.totals.accepted).isEqualTo(2)
        assertThat(snapshot.totals.inFlight).isZero()
        assertThat(snapshot.totals.postedAmountCents).isEqualTo(100)
        assertThat(runningInFlight(snapshot)).allMatch { it >= 0 }
        assertThat(snapshot.series.last().bankP50Ms).isNull()

        store(late)

        val complete = stats.snapshot(runId)
        assertThat(complete.series.first().accepted).isEqualTo(2)
        assertThat(complete.totals.postedAmountCents).isEqualTo(300)
        assertThat(complete.series.last().bankP50Ms).isEqualTo(1_500.0)
    }

    @Test
    fun `bank latency is processedAt minus acceptedAt, per second and over the run`() {
        val runId = UUID.randomUUID()
        val commands = (1..4).map { command(runId, 10, t0) }
        store(*commands.toTypedArray())
        store(*commands.mapIndexed { i, c -> posted(c, t0.plusMillis(100L * (i + 1))) }.toTypedArray())

        val snapshot = stats.snapshot(runId)

        assertThat(snapshot.bankLatency.p50Ms).isEqualTo(250.0)
        assertThat(snapshot.bankLatency.p99Ms).isEqualTo(397.0)
        assertThat(snapshot.series.single().bankP50Ms).isEqualTo(250.0)
    }

    @Test
    fun `storing the same events again changes nothing`() {
        val runId = UUID.randomUUID()
        val a = command(runId, 100, t0)
        store(a, a)
        store(posted(a, t0.plusMillis(5)))
        val before = stats.snapshot(runId)

        store(a)
        store(posted(a, t0.plusMillis(5)), posted(a, t0.plusMillis(5)))

        assertThat(stats.snapshot(runId).copy(at = before.at)).isEqualTo(before)
    }

    @Test
    fun `the API deletes a run once and then answers not found`() {
        val runId = UUID.randomUUID()
        store(command(runId, 100, t0))

        assertThat(http.getForObject("/internal/runs/$runId/stats", RunStats::class.java)!!.totals.accepted).isEqualTo(1)
        assertThat(delete(runId)).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(delete(runId)).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(stats.snapshot(runId).totals.accepted).isZero()
    }

    private fun delete(runId: UUID) =
        http.exchange("/internal/runs/$runId", HttpMethod.DELETE, null, Void::class.java).statusCode

    private fun runningInFlight(snapshot: RunStats): List<Long> =
        snapshot.series.runningFold(0L) { inFlight, s -> inFlight + s.accepted - s.posted - s.rejected }

    private fun store(vararg commands: TransferCommand) =
        transfers.upsertAccepted(commands.map { AcceptedTransfer(it.transferId, it.runId, it.amountCents, it.createdAt) })

    private fun store(vararg results: TransferResultEvent) =
        transfers.upsertOutcomes(results.map { TransferOutcomeRow(it.transferId, it.runId, it.outcome.name, it.rejectReason?.name, it.processedAt) })

    private fun entry(key: String, value: Long) = org.assertj.core.api.Assertions.entry(key, value)
}
