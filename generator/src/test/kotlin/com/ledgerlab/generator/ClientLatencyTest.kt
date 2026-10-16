package com.ledgerlab.generator

import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.UUID

class ClientLatencyTest {

    private fun simulation(gateway: FakeGateway, waitShare: Double, duplicateDelays: List<Duration> = emptyList()) = Simulation(
        runId = UUID.randomUUID(), targetTps = 20, duration = Duration.ofSeconds(1),
        profile = LoadProfile(UUID.randomUUID(), accountCount = 20, blockedAccounts = 2, hotAccounts = 3),
        waitShare = waitShare, waitTimeout = Duration.ofSeconds(1), stopGrace = Duration.ofSeconds(5),
        client = GatewayClient(testProperties(gateway.url), jacksonObjectMapper(), SimpleMeterRegistry()),
        maxInFlight = 500, registry = SimpleMeterRegistry(),
        duplicateShare = if (duplicateDelays.isEmpty()) 0.0 else 1.0, duplicateDelays = duplicateDelays,
    )

    private fun Simulation.finish() {
        start()
        await().atMost(Duration.ofSeconds(15)).until { !state.active }
    }

    @Test
    fun `the client latency is taken over every waiting first press of the run, not its duplicates`() {
        // A duplicate goes out 100 ms after the first press and, as a repeat, takes 300 ms.
        FakeGateway(responseDelay = Duration.ofMillis(30), repeatDelay = Duration.ofMillis(300)).use { gateway ->
            val simulation = simulation(gateway, waitShare = 1.0, duplicateDelays = listOf(Duration.ofMillis(100)))

            simulation.finish()

            val status = simulation.status()
            assertThat(status.waitedLatencyP50Ms).isNotNull().isBetween(30.0, 300.0)
            assertThat(status.waitedLatencyP99Ms).isNotNull().isGreaterThanOrEqualTo(status.waitedLatencyP50Ms).isLessThan(300.0)
        }
    }

    @Test
    fun `a final status to a client that did not wait gives no client latency`() {
        val alreadyFinal = { request: SubmitTransferRequest -> FakeGateway.bankAnswer(request, TransferStatus.POSTED) }
        FakeGateway(rawAnswer = alreadyFinal).use { gateway ->
            val simulation = simulation(gateway, waitShare = 0.0)

            simulation.finish()

            assertThat(simulation.status().acknowledged).isPositive()
            assertThat(simulation.status().waitedLatencyP50Ms).isNull()
            assertThat(simulation.status().waitedLatencyP99Ms).isNull()
        }
    }

    @Test
    fun `percentiles interpolate between ranks as the bank's SQL does`() {
        assertThat(percentileCont(emptyList(), 0.5)).isNull()
        assertThat(percentileCont(listOf(7), 0.99)).isEqualTo(7.0)
        assertThat(percentileCont(listOf(10, 20, 30, 40), 0.5)).isEqualTo(25.0)
        assertThat(percentileCont((1L..100L).toList(), 0.99)).isCloseTo(99.01, within(1e-9))
    }
}
