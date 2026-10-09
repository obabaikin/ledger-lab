package com.ledgerlab.generator

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

class SimulationTest {

    private fun client(url: String, maxAttempts: Int = 3, retryBackoff: Duration = Duration.ofMillis(50)) =
        GatewayClient(
            GeneratorProperties(url, Duration.ofSeconds(2), Duration.ofSeconds(10), maxAttempts, retryBackoff, 500, Duration.ofHours(1), Duration.ofMinutes(5)),
            ObjectMapper(),
        )

    private fun SimulationStatus.assertSettledCounts() {
        assertThat(inFlight).isZero()
        assertThat(acknowledged + failed + unknown).isEqualTo(requested)
    }

    private fun simulation(
        client: GatewayClient,
        tps: Int,
        seconds: Long,
        waitShare: Double = 0.0,
        stopGrace: Duration = Duration.ofSeconds(5),
    ) = Simulation(
        runId = UUID.randomUUID(), targetTps = tps, duration = Duration.ofSeconds(seconds),
        profile = LoadProfile(UUID.randomUUID(), accountCount = 20, blockedAccounts = 2, hotAccounts = 3),
        waitShare = waitShare, waitTimeout = Duration.ofSeconds(1), stopGrace = stopGrace, client = client,
        maxInFlight = 500, registry = SimpleMeterRegistry(),
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
        }
    }
}
