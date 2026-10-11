package com.ledgerlab.generator

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SimulationControllerTest {

    private fun controller(
        gateway: FakeGateway,
        journal: JournalProperties = JournalProperties(Duration.ofMinutes(10), maxEntries = 1_000),
    ): SimulationController {
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

            await().atMost(Duration.ofSeconds(15)).until { controller.status(first.runId).state == SimulationState.FINISHED }
        }
    }

    @Test
    fun `a repeated start of an active run is told it is running, not that memory is full`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway, JournalProperties(Duration.ofMinutes(10), maxEntries = 100))
            val run = request(tps = 100, seconds = 1)
            controller.start(run)

            assertThatThrownBy { controller.start(run) }.isInstanceOf(SimulationAlreadyRunningException::class.java)

            await().atMost(Duration.ofSeconds(15)).until { controller.status(run.runId!!).state == SimulationState.FINISHED }
        }
    }

    @Test
    fun `a released journal frees its share of the cap, the status stays readable`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway, JournalProperties(Duration.ZERO, maxEntries = 100))
            val run = controller.start(request(tps = 80, seconds = 1))
            await().atMost(Duration.ofSeconds(15)).until { controller.status(run.runId).state == SimulationState.FINISHED }
            assertThatThrownBy { controller.start(request(tps = 80, seconds = 1)) }
                .isInstanceOf(JournalCapacityException::class.java)

            controller.evictFinished()

            assertThat(controller.status(run.runId).requested).isGreaterThan(0)
            assertThatThrownBy { controller.transfers(run.runId, null, null, null, 0, 100) }
                .isInstanceOf(NotFoundException::class.java)
            assertThat(controller.start(request(tps = 80, seconds = 1)).state).isEqualTo(SimulationState.RUNNING)
        }
    }

    private fun SimulationController.finished(tps: Int = 30): UUID {
        val run = start(request(tps = tps, seconds = 1))
        await().atMost(Duration.ofSeconds(15)).until { status(run.runId).state == SimulationState.FINISHED }
        return run.runId
    }

    @Test
    fun `a finished run reconciles against every page of the statement`() {
        FakeGateway(statementPage = 7).use { gateway ->
            val controller = controller(gateway)
            val runId = controller.finished()

            val report = controller.reconciliation(runId)

            assertThat(report.balanced).isTrue()
            assertThat(report.statementFinal).isTrue()
            assertThat(report.journal.requested).isGreaterThan(7)
            assertThat(report.statement.rows).isEqualTo(report.journal.acknowledged).isEqualTo(report.journal.requested)
        }
    }

    @Test
    fun `a run that may still send is not reconciled`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway)
            val run = controller.start(request(tps = 10, seconds = 10))

            assertThatThrownBy { controller.reconciliation(run.runId) }.isInstanceOf(RunStillActiveException::class.java)

            controller.stop(run.runId)
            await().atMost(Duration.ofSeconds(15)).until { !controller.status(run.runId).state.active }
            assertThat(controller.reconciliation(run.runId).balanced).isTrue()
        }
    }

    @Test
    fun `a period the gateway has not closed is passed on as not closed`() {
        FakeGateway(periodClosed = false).use { gateway ->
            val controller = controller(gateway)
            val runId = controller.finished()

            assertThatThrownBy { controller.reconciliation(runId) }
                .isInstanceOf(PeriodNotClosedException::class.java)
                .hasMessage(GeneratorErrors.PERIOD_NOT_CLOSED)
        }
    }

    @Test
    fun `a 5xx on a later page makes the gateway unavailable, a 4xx is a bad answer`() {
        FakeGateway(statementPage = 7, statementFailure = { page -> if (page == 2) 503 else null }).use { gateway ->
            val controller = controller(gateway)
            val runId = controller.finished()

            assertThatThrownBy { controller.reconciliation(runId) }.isInstanceOf(GatewayUnavailableException::class.java)
            assertThat(controller.reconciliation(runId).balanced).isTrue()
        }
        FakeGateway(statementFailure = { 400 }).use { gateway ->
            val controller = controller(gateway)
            val runId = controller.finished()

            assertThatThrownBy { controller.reconciliation(runId) }.isInstanceOf(BadGatewayAnswerException::class.java)
        }
    }

    @Test
    fun `a finished run started again under its id does not count against the cap twice`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway, JournalProperties(Duration.ofMinutes(10), maxEntries = 100))
            val run = request(tps = 80, seconds = 1)
            controller.start(run)
            await().atMost(Duration.ofSeconds(15)).until { controller.status(run.runId!!).state == SimulationState.FINISHED }

            assertThat(controller.start(run).state).isEqualTo(SimulationState.RUNNING)
        }
    }

    @Test
    fun `an unreadable statement page is a bad answer`() {
        val bodies = listOf(
            "null",
            "not json",
            """{"runId":"${UUID.randomUUID()}","closedAt":"${Instant.now()}","items":[null],"next":null}""",
        )
        for (body in bodies) {
            FakeGateway(rawStatement = body).use { gateway ->
                val controller = controller(gateway)
                val runId = controller.finished()

                assertThatThrownBy { controller.reconciliation(runId) }.`as`(body).isInstanceOf(BadGatewayAnswerException::class.java)
            }
        }
    }

    @Test
    fun `a gateway that cannot be reached for the statement is unavailable`() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        val runId = controller.finished()
        gateway.close()

        assertThatThrownBy { controller.reconciliation(runId) }.isInstanceOf(GatewayUnavailableException::class.java)
    }

    @Test
    fun `a released journal cannot be reconciled`() {
        FakeGateway().use { gateway ->
            val controller = controller(gateway, JournalProperties(Duration.ZERO, maxEntries = 1_000))
            val runId = controller.finished()
            controller.evictFinished()

            assertThatThrownBy { controller.reconciliation(runId) }
                .isInstanceOf(NotFoundException::class.java)
                .hasMessageContaining("released")
        }
    }

    @Test
    @Timeout(10)
    fun `a statement that pages back to a cursor it gave before is a bad answer, not an endless loop`() {
        FakeGateway(rawStatement = """{"items":[],"next":"${UUID.randomUUID()}"}""").use { gateway ->
            val controller = controller(gateway)
            val runId = controller.finished()

            assertThatThrownBy { controller.reconciliation(runId) }
                .isInstanceOf(BadGatewayAnswerException::class.java)
                .hasMessageContaining("pages back")
        }
    }

    @Test
    fun `over HTTP an unavailable gateway answers 503 and a bad answer 502`() {
        for ((gateway, expected) in listOf(
            FakeGateway(statementFailure = { HttpStatus.SERVICE_UNAVAILABLE.value() }) to HttpStatus.SERVICE_UNAVAILABLE,
            FakeGateway(rawStatement = "not json") to HttpStatus.BAD_GATEWAY,
        )) {
            gateway.use {
                val controller = controller(gateway)
                val runId = controller.finished()

                MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(MockMvcRequestBuilders.get("/internal/simulations/$runId/reconciliation"))
                    .andExpect(MockMvcResultMatchers.status().`is`(expected.value()))
            }
        }
    }
}
