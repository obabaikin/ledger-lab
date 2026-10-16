package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.TransferStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.UUID

class SimulationTest {

    private fun client(url: String, maxAttempts: Int = 3, retryBackoff: Duration = Duration.ofMillis(50)) =
        GatewayClient(testProperties(url, maxAttempts, retryBackoff), jacksonObjectMapper(), SimpleMeterRegistry())

    private fun SimulationStatus.assertSettledCounts() {
        assertThat(inFlight).isZero()
        assertThat(acknowledged + failed + unknown).isEqualTo(requested)
    }

    private fun Simulation.entries(): List<JournalEntryView> =
        journal!!.page(JournalFilter(), after = 0, limit = Int.MAX_VALUE, complete = true).items

    /** The journal and the counters are kept apart; they must tell the same story. */
    private fun Simulation.assertJournalMatchesStatus() {
        val status = status()
        val entries = entries()
        assertThat(entries).hasSize(status.requested.toInt())
        assertThat(entries.map { it.seq }).isEqualTo((1L..entries.size).toList())
        assertThat(entries.map { it.transferId }).doesNotHaveDuplicates()
        assertThat(entries).allSatisfy { assertThat(it.first).isNotNull() }
        val byOutcome = entries.groupingBy { it.outcome }.eachCount()
        assertThat(byOutcome[JournalOutcome.PENDING]).isNull()
        assertThat(byOutcome[JournalOutcome.ACKNOWLEDGED] ?: 0).isEqualTo(status.acknowledged.toInt())
        assertThat(byOutcome[JournalOutcome.FAILED] ?: 0).isEqualTo(status.failed.toInt())
        assertThat(byOutcome[JournalOutcome.UNKNOWN] ?: 0).isEqualTo(status.unknown.toInt())
        assertThat(entries.sumOf { e -> e.resends.count { it.kind == ResendKind.RETRY } }).isEqualTo(status.retries.toInt())
        assertThat(entries.sumOf { e -> e.resends.count { it.kind == ResendKind.DUPLICATE } }).isEqualTo(status.duplicatesSent.toInt())
        assertThat(entries.count { it.gatewayStatus == TransferStatus.POSTED }).isEqualTo(status.posted.toInt())
        assertThat(entries.count { it.gatewayStatus == TransferStatus.REJECTED }).isEqualTo(status.rejected.toInt())
        assertThat(entries.mapNotNull { it.rejectReason }.groupingBy { it }.eachCount().mapValues { it.value.toLong() })
            .isEqualTo(status.rejectedByReason)
        assertThat(status.rejectedByReason.values.sum()).isEqualTo(status.rejected)
    }

    private fun simulation(
        client: GatewayClient,
        tps: Int,
        seconds: Long,
        waitShare: Double = 0.0,
        stopGrace: Duration = Duration.ofSeconds(5),
        duplicateShare: Double = 0.0,
        duplicateDelays: List<Duration> = emptyList(),
        registry: MeterRegistry = SimpleMeterRegistry(),
    ) = Simulation(
        runId = UUID.randomUUID(), targetTps = tps, duration = Duration.ofSeconds(seconds),
        profile = LoadProfile(UUID.randomUUID(), accountCount = 20, blockedAccounts = 2, hotAccounts = 3),
        waitShare = waitShare, waitTimeout = Duration.ofSeconds(1), stopGrace = stopGrace, client = client,
        maxInFlight = 500, registry = registry, duplicateShare = duplicateShare, duplicateDelays = duplicateDelays,
    )

    @Test
    fun `holds the requested rate and gets every submission acknowledged`() {
        FakeGateway().use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 200, seconds = 2, waitShare = 0.2)

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(status.requested).isBetween(360, 440)
            assertThat(status.acknowledged).isEqualTo(status.requested)
            assertThat(status.failed).isZero()
            assertThat(status.unknown).isZero()
            assertThat(status.retries).isZero()
            assertThat(status.posted).isGreaterThan(0)
            assertThat(gateway.attemptsById).hasSize(status.requested.toInt())
            assertThat(gateway.attemptsById.values.map { it.get() }).containsOnly(1)
            simulation.assertJournalMatchesStatus()
            assertThat(simulation.entries()).allSatisfy {
                assertThat(it.first!!.result).isEqualTo(SendResult.RESPONSE)
                assertThat(it.first!!.httpStatus).isIn(200, 202)
                assertThat(it.resends).isEmpty()
            }
        }
    }

    @Test
    fun `counts new transfers per second of the run, a slot for each second begun, adding up to the requested`() {
        FakeGateway().use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 200, seconds = 3)

            simulation.start()
            Thread.sleep(1_500)
            val midway = simulation.status().requestedPerSecond
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(midway).hasSize(2)
            assertThat(status.requestedPerSecond).hasSize(4)
            assertThat(status.requestedPerSecond.sum()).isEqualTo(status.requested)
            assertThat(status.requestedPerSecond.take(3)).allSatisfy { assertThat(it).isBetween(150L, 250L) }
        }
    }

    @Test
    fun `retries a failed submission with the same transfer id`() {
        FakeGateway(failFirstAttempt = true).use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 50, seconds = 1)

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(status.failed).isZero()
            assertThat(status.retries).isEqualTo(status.requested)
            assertThat(gateway.attemptsById.values.map { it.get() }).containsOnly(2)
            simulation.assertJournalMatchesStatus()
            assertThat(simulation.entries()).allSatisfy { entry ->
                assertThat(entry.first!!.httpStatus).isEqualTo(503)
                assertThat(entry.resends).singleElement().satisfies({ resend ->
                    assertThat(resend.kind).isEqualTo(ResendKind.RETRY)
                    assertThat(resend.number).isEqualTo(2)
                    assertThat(resend.result).isEqualTo(SendResult.RESPONSE)
                    assertThat(resend.httpStatus).isEqualTo(202)
                    assertThat(resend.offsetMs).isGreaterThanOrEqualTo(entry.first!!.offsetMs + entry.first!!.latencyMs)
                })
            }
        }
    }

    @Test
    fun `a 5xx on the last attempt leaves the outcome unknown, not failed`() {
        FakeGateway(failFirstAttempt = true).use { gateway ->
            val simulation = simulation(client(gateway.url, maxAttempts = 1), tps = 20, seconds = 1)

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(status.unknown).isEqualTo(status.requested)
            assertThat(status.failed).isZero()
            assertThat(status.acknowledged).isZero()
            status.assertSettledCounts()
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `a 4xx is a failure and is not retried`() {
        FakeGateway(refuseWith = 422).use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 20, seconds = 1)

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(status.failed).isEqualTo(status.requested)
            assertThat(status.unknown).isZero()
            assertThat(status.retries).isZero()
            status.assertSettledCounts()
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `a stop lets open requests finish so the counters add up`() {
        FakeGateway(responseDelay = Duration.ofMillis(300)).use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 100, seconds = 30)
            simulation.start()
            await().atMost(Duration.ofSeconds(5)).until { simulation.status().inFlight > 10 }

            simulation.stop()
            assertThat(simulation.state).isEqualTo(SimulationState.STOPPING)
            await().atMost(Duration.ofSeconds(10)).until { simulation.state == SimulationState.STOPPED }

            val status = simulation.status()
            assertThat(status.inFlight).isZero()
            assertThat(status.failed).isZero()
            assertThat(status.unknown).isZero()
            assertThat(status.acknowledged).isEqualTo(status.requested)
            assertThat(gateway.requests.get().toLong()).isEqualTo(status.requested)
        }
    }

    @Test
    fun `requests still open after the grace period are cancelled and counted as unknown`() {
        FakeGateway(responseDelay = Duration.ofSeconds(5)).use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 100, seconds = 30, stopGrace = Duration.ofMillis(200))
            simulation.start()
            await().atMost(Duration.ofSeconds(5)).until { simulation.status().inFlight > 10 }

            simulation.stop()
            await().atMost(Duration.ofSeconds(5)).until { simulation.state == SimulationState.STOPPED }

            val status = simulation.status()
            assertThat(status.failed).isZero()
            assertThat(status.unknown).isGreaterThan(0)
            status.assertSettledCounts()
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `no retries after a stop, and an unanswered 5xx stays unknown`() {
        FakeGateway(failFirstAttempt = true).use { gateway ->
            val simulation = simulation(client(gateway.url, retryBackoff = Duration.ofSeconds(1)), tps = 50, seconds = 30)
            simulation.start()
            await().atMost(Duration.ofSeconds(5)).until { simulation.status().requested > 10 }

            simulation.stop()
            await().atMost(Duration.ofSeconds(10)).until { simulation.state == SimulationState.STOPPED }

            val status = simulation.status()
            assertThat(gateway.attemptsById.values.map { it.get() }).containsOnly(1)
            assertThat(status.unknown).isEqualTo(status.requested)
            assertThat(status.failed).isZero()
            status.assertSettledCounts()
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `a ledger rejection keeps its reason in the journal and in the status`() {
        FakeGateway(rejectReason = RejectReason.INSUFFICIENT_FUNDS).use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 20, seconds = 1, waitShare = 1.0)

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(status.rejected).isEqualTo(status.requested)
            assertThat(status.rejectedByReason).containsExactly(entry(RejectReason.INSUFFICIENT_FUNDS, status.requested))
            simulation.assertJournalMatchesStatus()
            assertThat(simulation.entries()).allSatisfy {
                assertThat(it.outcome).isEqualTo(JournalOutcome.ACKNOWLEDGED)
                assertThat(it.gatewayStatus).isEqualTo(TransferStatus.REJECTED)
                assertThat(it.rejectReason).isEqualTo(RejectReason.INSUFFICIENT_FUNDS)
            }
        }
    }

    @Test
    fun `a send cut off by the grace period is recorded as cancelled`() {
        FakeGateway(responseDelay = Duration.ofSeconds(5)).use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 100, seconds = 30, stopGrace = Duration.ofMillis(200))
            simulation.start()
            await().atMost(Duration.ofSeconds(5)).until { simulation.status().inFlight > 10 }

            simulation.stop()
            await().atMost(Duration.ofSeconds(5)).until { simulation.state == SimulationState.STOPPED }

            val cancelled = simulation.entries().filter { it.first!!.result == SendResult.CANCELLED }
            assertThat(cancelled).isNotEmpty()
            assertThat(cancelled).allSatisfy {
                assertThat(it.outcome).isEqualTo(JournalOutcome.UNKNOWN)
                assertThat(it.first!!.httpStatus).isNull()
            }
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `entries are readable while the run is going and start as pending`() {
        FakeGateway(responseDelay = Duration.ofMillis(500)).use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 50, seconds = 30)
            simulation.start()
            await().atMost(Duration.ofSeconds(5)).until { simulation.status().inFlight > 5 }

            val page = simulation.journal!!.page(JournalFilter(outcome = JournalOutcome.PENDING), 0, 1_000, complete = false)
            assertThat(page.items).isNotEmpty()
            assertThat(page.next).isNotNull()

            simulation.stop()
            await().atMost(Duration.ofSeconds(10)).until { simulation.state == SimulationState.STOPPED }
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `a released journal is gone, the status stays`() {
        FakeGateway().use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 20, seconds = 1)
            simulation.start()
            assertThatThrownBy { simulation.releaseJournal() }.isInstanceOf(IllegalStateException::class.java)
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            simulation.releaseJournal()

            assertThat(simulation.journal).isNull()
            assertThat(simulation.status().requested).isGreaterThan(0)
        }
    }

    @Test
    fun `a duplicate is a second send of the same transfer, not a second transfer`() {
        FakeGateway().use { gateway ->
            val registry = SimpleMeterRegistry()
            val simulation = simulation(
                client(gateway.url), tps = 50, seconds = 1, waitShare = 1.0,
                duplicateShare = 1.0, duplicateDelays = listOf(Duration.ZERO, Duration.ofMillis(50)), registry = registry,
            )

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(status.requested).isEqualTo(50)
            assertThat(status.duplicatesSent).isEqualTo(status.requested)
            assertThat(status.acknowledged).isEqualTo(status.requested)
            assertThat(status.retries).isZero()
            assertThat(gateway.attemptsById).hasSize(50)
            assertThat(gateway.attemptsById.values.map { it.get() }).containsOnly(2)
            simulation.assertJournalMatchesStatus()
            assertThat(simulation.entries()).allSatisfy {
                assertThat(it.resends).singleElement().satisfies({ resend -> assertThat(resend.kind).isEqualTo(ResendKind.DUPLICATE) })
            }

            fun sends(kind: String) = registry.get("generator.send.latency")
                .tags("waited", "true", "kind", kind, "final", "true").timer().count()
            assertThat(sends("first")).isEqualTo(50)
            assertThat(sends("duplicate")).isEqualTo(50)
            assertThat(registry.get("generator.submission.latency").tag("kind", "duplicate").timer().count()).isEqualTo(50)
        }
    }

    @Test
    fun `an answer to either send acknowledges the transfer`() {
        // Whichever send of a transfer arrives first gets the 503; with a single attempt alone it would stay unknown.
        FakeGateway(failFirstAttempt = true).use { gateway ->
            val simulation = simulation(
                client(gateway.url, maxAttempts = 1), tps = 20, seconds = 1,
                duplicateShare = 1.0, duplicateDelays = listOf(Duration.ZERO),
            )

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            val status = simulation.status()
            assertThat(status.acknowledged).isEqualTo(status.requested)
            assertThat(status.unknown).isZero()
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `a stop cancels duplicates that are still waiting for their moment`() {
        FakeGateway().use { gateway ->
            val simulation = simulation(
                client(gateway.url), tps = 50, seconds = 30,
                duplicateShare = 1.0, duplicateDelays = listOf(Duration.ofSeconds(20)),
            )
            simulation.start()
            await().atMost(Duration.ofSeconds(5)).until { simulation.status().requested > 10 }

            simulation.stop()
            await().atMost(Duration.ofSeconds(3)).until { simulation.state == SimulationState.STOPPED }

            val status = simulation.status()
            assertThat(status.duplicatesSent).isZero()
            assertThat(gateway.attemptsById.values.map { it.get() }).containsOnly(1)
            assertThat(status.acknowledged).isEqualTo(status.requested)
            simulation.assertJournalMatchesStatus()
        }
    }

    @Test
    fun `the pacer never launches more than the journal reserved`() {
        // 1s / 7 is rounded down to whole nanoseconds, which would make a 22nd send due just before the end.
        FakeGateway().use { gateway ->
            val simulation = simulation(client(gateway.url), tps = 7, seconds = 3)

            simulation.start()
            await().atMost(Duration.ofSeconds(15)).until { simulation.state == SimulationState.FINISHED }

            assertThat(simulation.status().requested).isEqualTo(simulation.journalCapacity).isEqualTo(21)
        }
    }

    @Test
    fun `a duplicate cut off by a stop does not undo the answer the first send got`() {
        FakeGateway(repeatDelay = Duration.ofSeconds(5)).use { gateway ->
            val simulation = simulation(
                client(gateway.url), tps = 50, seconds = 30, stopGrace = Duration.ofMillis(300),
                duplicateShare = 1.0, duplicateDelays = listOf(Duration.ofMillis(50)),
            )
            simulation.start()
            await().atMost(Duration.ofSeconds(5)).until { simulation.status().duplicatesSent > 5 }

            simulation.stop()
            await().atMost(Duration.ofSeconds(5)).until { simulation.state == SimulationState.STOPPED }

            val status = simulation.status()
            assertThat(status.unknown).isZero()
            assertThat(status.acknowledged).isEqualTo(status.requested)
            assertThat(simulation.entries().flatMap { it.resends }.map { it.result }).contains(SendResult.CANCELLED)
            simulation.assertJournalMatchesStatus()
        }
    }
}
