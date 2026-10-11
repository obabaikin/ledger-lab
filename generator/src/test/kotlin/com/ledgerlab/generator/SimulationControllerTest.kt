package com.ledgerlab.generator

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.UUID

class SimulationControllerTest {

    private fun controller(gateway: FakeGateway, journal: JournalProperties): SimulationController {
        val props = testProperties(gateway.url, journal = journal)
        return SimulationController(GatewayClient(props, jacksonObjectMapper(), SimpleMeterRegistry()), props, SimpleMeterRegistry())
    }

    private fun request(tps: Int, seconds: Int) =
        StartSimulationRequest(UUID.randomUUID(), tps, seconds, accountCount = 10)

    @Test
    fun `a start that does not fit next to the journals in memory is refused, not squeezed in`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway, JournalProperties(Duration.ofMinutes(10), maxEntries = 100))

            val first = controller.start(request(tps = 60, seconds = 1))
            assertThatThrownBy { controller.start(request(tps = 50, seconds = 1)) }
                .isInstanceOf(JournalCapacityException::class.java)
            assertThatThrownBy { controller.start(request(tps = 101, seconds = 1)) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(controller.start(request(tps = 40, seconds = 1)).state).isEqualTo(SimulationState.RUNNING)

            await().atMost(Duration.ofSeconds(15)).until { controller.status(first.runId).body!!.state == SimulationState.FINISHED }
        }
    }

    @Test
    fun `a repeated start of an active run is told it is running, not that memory is full`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway, JournalProperties(Duration.ofMinutes(10), maxEntries = 100))
            val run = request(tps = 100, seconds = 1)
            controller.start(run)

            assertThatThrownBy { controller.start(run) }.isInstanceOf(SimulationAlreadyRunningException::class.java)

            await().atMost(Duration.ofSeconds(15)).until { controller.status(run.runId!!).body!!.state == SimulationState.FINISHED }
        }
    }

    @Test
    fun `a released journal frees its share of the cap, the status stays readable`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway, JournalProperties(Duration.ZERO, maxEntries = 100))
            val run = controller.start(request(tps = 80, seconds = 1))
            await().atMost(Duration.ofSeconds(15)).until { controller.status(run.runId).body!!.state == SimulationState.FINISHED }
            assertThatThrownBy { controller.start(request(tps = 80, seconds = 1)) }
                .isInstanceOf(JournalCapacityException::class.java)

            controller.evictFinished()

            assertThat(controller.status(run.runId).body!!.requested).isGreaterThan(0)
            assertThatThrownBy { controller.transfers(run.runId, null, null, null, 0, 100) }
                .isInstanceOf(NotFoundException::class.java)
            assertThat(controller.start(request(tps = 80, seconds = 1)).state).isEqualTo(SimulationState.RUNNING)
        }
    }
}
