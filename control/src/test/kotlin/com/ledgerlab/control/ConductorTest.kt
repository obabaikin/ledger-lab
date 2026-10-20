package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.books
import com.ledgerlab.control.RunFixtures.duplicates
import com.ledgerlab.control.RunFixtures.clientReport
import com.ledgerlab.control.RunFixtures.simulation
import com.ledgerlab.control.RunFixtures.stats
import com.ledgerlab.control.RunFixtures.summary
import com.ledgerlab.control.gateway.GatewayApi
import com.ledgerlab.control.generator.GeneratorApi
import com.ledgerlab.control.http.forRun
import com.ledgerlab.control.ledger.LedgerApi
import com.ledgerlab.control.reporting.ReportingApi
import com.ledgerlab.control.runs.Conductor
import com.ledgerlab.control.runs.RunQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

class ConductorTest : IntegrationTest() {

    private companion object {
        const val PROMETHEUS_QUERY = "/api/v1/query"
    }

    @Autowired lateinit var conductor: Conductor
    @Autowired lateinit var queue: RunQueue

    private val closedAt = "2026-10-12T01:00:00Z"

    private fun admitted(): UUID {
        val runId = UUID.fromString(start(sessionOf(invite())).body!!["runId"] as String)
        queue.advance(ledgerReady = true)
        return runId
    }

    private fun running(): UUID = admitted().also {
        jdbc.sql("UPDATE runs SET state = 'RUNNING', started_at = clock_timestamp() WHERE run_id = :id").param("id", it).update()
    }

    private fun draining(closed: Boolean = true, clientLost: Boolean = false): UUID = running().also {
        jdbc.sql(
            """
            UPDATE runs SET state = 'DRAINING', drain_started_at = clock_timestamp(), client_lost = :clientLost,
                closed_at = CASE WHEN :closed THEN clock_timestamp() END,
                drain_progress = CASE WHEN :closed THEN 0 END,
                drain_progress_at = CASE WHEN :closed THEN clock_timestamp() END
            WHERE run_id = :id
            """,
        ).param("id", it).param("closed", closed).param("clientLost", clientLost).update()
    }

    private fun backdate(runId: UUID, column: String, by: Duration) {
        jdbc.sql("UPDATE runs SET $column = $column - CAST(:millis AS bigint) * interval '1 millisecond' WHERE run_id = :id")
            .param("millis", by.toMillis())
            .param("id", runId)
            .update()
    }

    private fun column(runId: UUID, column: String): Any? =
        jdbc.sql("SELECT $column FROM runs WHERE run_id = :id").param("id", runId).query { rs, _ -> rs.getObject(1) }.list().single()

    private fun bank(
        runId: UUID,
        summary: Map<String, Any?> = summary(runId),
        books: Map<String, Any?> = books(runId),
        stats: Map<String, Any?>? = stats(runId),
    ) {
        gateway.answer("GET", GatewayApi.SUMMARY_PATH.forRun(runId), 200, summary)
        ledger.answer("GET", LedgerApi.RECONCILIATION_PATH.forRun(runId), 200, books)
        stats?.let { reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 200, it) }
    }

    private fun clientReconciles(runId: UUID, status: Int = 200, body: Any? = clientReport(runId)) =
        generator.answer("GET", GeneratorApi.RECONCILIATION_PATH.forRun(runId), status, body)

    private fun reportOf(sessionRun: UUID): Map<*, *> {
        val sessionId = jdbc.sql("SELECT session_id FROM runs WHERE run_id = :id").param("id", sessionRun).query(UUID::class.java).single()
        return http.getForEntity("/api/sessions/$sessionId/runs/$sessionRun/report", Map::class.java).body!!
    }

    @Test
    fun `a created run gets its accounts, then its clients, and runs`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404, mapOf("error" to "No simulation $runId"))
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(runId, "RUNNING"))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RUNNING")
        assertThat(column(runId, "started_at")).isNotNull()
        val accounts = objectMapper.readTree(ledger.callsTo("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId)).single().body)
        assertThat(accounts.get("accountCount").asInt()).isEqualTo(100)
        assertThat(accounts.get("openingBalanceCents").asLong()).isEqualTo(100_000_000)
        assertThat(accounts.get("blockedAccounts").asInt()).isEqualTo(2)
        assertThat(accounts.get("dailyLimitCents").asLong()).isEqualTo(20_000_000)
        val simulation = objectMapper.readTree(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH).single().body)
        assertThat(simulation.propertyNames()).containsExactlyInAnyOrder("runId", "tps", "durationSeconds", "accountCount", "blockedAccounts")
        assertThat(simulation.get("accountCount").asInt()).isEqualTo(100)
        assertThat(simulation.get("blockedAccounts").asInt()).isEqualTo(2)
    }

    @Test
    fun `accounts an earlier attempt already opened count as opened`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 409, mapOf("error" to "Run $runId already has accounts"))
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(runId, "RUNNING"))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RUNNING")
    }

    @Test
    fun `clients the generator already runs are not started again`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200, simulation(runId, "FINISHED"))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RUNNING")
        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).isEmpty()
        assertThat(ledger.callsTo("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId))).isEmpty()
    }

    @Test
    fun `a setup the ledger rejects fails the run`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 400, mapOf("error" to "invalid"))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("FAILED")
        assertThat(column(runId, "failure")).isEqualTo("setup rejected")
        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).isEmpty()
    }

    @Test
    fun `a setup the generator rejects fails the run`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 400, mapOf("error" to "invalid"))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("FAILED")
        assertThat(column(runId, "failure")).isEqualTo("setup rejected")
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `a setup that keeps failing is retried, then fails after the setup timeout and logs the last answers`(output: CapturedOutput) {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 409, mapOf("error" to "journals in memory hold 300000"))

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("CREATED")

        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 503, mapOf("error" to "gateway unavailable"))
        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("CREATED")

        backdate(runId, "setup_started_at", props.runs.setupTimeout)
        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("FAILED")
        assertThat(column(runId, "failure")).isEqualTo("setup timed out")
        val line = output.out.lines().single { "setup timed out" in it && runId.toString() in it }
        assertThat(line).contains("generator status: 404", "ledger accounts: answered 201", "generator start: 503 gateway unavailable")
    }

    @Test
    fun `a run first tried long after it left the queue gets its whole setup time`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(runId, "RUNNING"))
        backdate(runId, "admitted_at", props.runs.setupTimeout.plusSeconds(60))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RUNNING")
    }

    @Test
    fun `a setup that times out after a start that may have arrived stops the clients`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 503)
        conductor.tick()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 503)

        backdate(runId, "setup_started_at", props.runs.setupTimeout)
        conductor.tick()

        assertThat(column(runId, "failure")).isEqualTo("setup timed out")
        assertThat(generator.callsTo("POST", GeneratorApi.STOP_PATH.forRun(runId))).hasSize(1)
    }

    @Test
    fun `a setup that times out with no start sent stops nothing`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 409, mapOf("error" to "journals in memory hold 300000"))
        conductor.tick()

        backdate(runId, "setup_started_at", props.runs.setupTimeout)
        conductor.tick()

        assertThat(column(runId, "failure")).isEqualTo("setup timed out")
        assertThat(generator.callsTo("POST", GeneratorApi.STOP_PATH.forRun(runId))).isEmpty()
    }

    @Test
    fun `a start whose answer was lost is not sent again when the generator forgot the run`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 503)

        conductor.tick()
        conductor.tick()

        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).hasSize(1)
        assertThat(stateOf(runId)).isEqualTo("RUNNING")
        conductor.tick()
        assertThat(column(runId, "client_lost")).isEqualTo(true)
    }

    @Test
    fun `a start the generator refused is sent again`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 409, mapOf("error" to "journals in memory hold 300000"))

        conductor.tick()
        conductor.tick()

        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).hasSize(2)
        assertThat(stateOf(runId)).isEqualTo("CREATED")
    }

    @Test
    fun `an answer control cannot read does not keep the run past its deadline`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200, "{\"state\":\"WARMING_UP\"}")

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("CREATED")

        backdate(runId, "setup_started_at", props.runs.setupTimeout)
        conductor.tick()
        assertThat(column(runId, "failure")).isEqualTo("setup timed out")
    }

    @Test
    fun `a slow step of one run does not hold up another run`() {
        val slow = draining()
        bank(slow)
        clientReconciles(slow)
        generator.answer("GET", GeneratorApi.RECONCILIATION_PATH.forRun(slow), 200, clientReport(slow), delay = props.generator.reconciliationTimeout.minusMillis(100))
        backdate(slow, "admitted_at", Duration.ofHours(1))
        val fresh = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(fresh), 404)
        ledger.answer("POST", LedgerApi.ACCOUNTS_PATH.forRun(fresh), 201, emptyList<Any>())
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(fresh, "RUNNING"))
        jdbc.sql("UPDATE runs SET setup_started_at = clock_timestamp() - CAST(:millis AS bigint) * interval '1 millisecond' WHERE run_id = :id")
            .param("millis", props.runs.setupTimeout.minusMillis(300).toMillis())
            .param("id", fresh)
            .update()

        conductor.tick()

        assertThat(stateOf(fresh)).isEqualTo("RUNNING")
        assertThat(stateOf(slow)).isEqualTo("RECONCILED")
    }

    @Test
    fun `a generator that does not answer in time is asked again, not started over`() {
        val runId = admitted()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404, delay = props.services.requestTimeout.plusMillis(300))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("CREATED")
        assertThat(ledger.callsTo("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId))).isEmpty()
        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).isEmpty()
    }

    @Test
    fun `clients that finished or were stopped hand the run over to the drain`() {
        val finished = running()
        val stopped = running()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(finished), 200, simulation(finished, "FINISHED"))
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(stopped), 200, simulation(stopped, "STOPPED"))

        conductor.tick()

        assertThat(listOf(stateOf(finished), stateOf(stopped))).containsOnly("DRAINING")
        assertThat(column(finished, "client_lost")).isEqualTo(false)
    }

    @Test
    fun `active clients keep the run running`() {
        val runId = running()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200, simulation(runId, "STOPPING"))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RUNNING")
    }

    @Test
    fun `clients the generator no longer knows are lost, and the run still drains`() {
        val runId = running()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 404)

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("DRAINING")
        assertThat(column(runId, "client_lost")).isEqualTo(true)
    }

    @Test
    fun `clients still active past the duration and the grace are stopped and the run drains`() {
        val runId = running()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200, simulation(runId, "RUNNING"))
        backdate(runId, "started_at", Duration.ofSeconds(10).plus(props.runs.runningGrace))

        conductor.tick()

        assertThat(generator.callsTo("POST", GeneratorApi.STOP_PATH.forRun(runId))).hasSize(1)
        assertThat(stateOf(runId)).isEqualTo("DRAINING")
    }

    @Test
    fun `a requested stop is passed to the generator on the next tick`() {
        val runId = running()
        val sessionId = column(runId, "session_id")
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200, simulation(runId, "STOPPING"))

        val answer = http.postForEntity("/api/sessions/$sessionId/runs/$runId/stop", null, Map::class.java)
        assertThat(answer.statusCode.value()).isEqualTo(202)
        assertThat(answer.body!!["stopRequested"]).isEqualTo(true)

        conductor.tick()

        assertThat(generator.callsTo("POST", GeneratorApi.STOP_PATH.forRun(runId))).hasSize(1)
    }

    @Test
    fun `the drain starts by closing the period`() {
        val runId = draining(closed = false)
        gateway.answer("POST", GatewayApi.CLOSE_PATH.forRun(runId), 200, mapOf("runId" to runId, "closedAt" to closedAt))

        conductor.tick()

        assertThat(column(runId, "closed_at")).isNotNull()
        assertThat(column(runId, "drain_progress")).isEqualTo(0L)
    }

    @Test
    fun `a close that keeps failing fails the run after the drain timeout`() {
        val runId = draining(closed = false)
        gateway.answer("POST", GatewayApi.CLOSE_PATH.forRun(runId), 503, mapOf("error" to "lock timeout"))

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("DRAINING")

        backdate(runId, "drain_started_at", props.runs.drainTimeout)
        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("FAILED")
        assertThat(column(runId, "failure")).isEqualTo("period not closed")
    }

    @Test
    fun `a drained run is reconciled once and its report kept`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId)

        conductor.tick()
        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RECONCILED")
        assertThat(column(runId, "result")).isEqualTo("BALANCED")
        assertThat(generator.callsTo("GET", GeneratorApi.RECONCILIATION_PATH.forRun(runId))).hasSize(1)
        val report = reportOf(runId)
        assertThat(report["result"]).isEqualTo("BALANCED")
        assertThat(report["reasons"] as List<*>).isEmpty()
        assertThat(report["bankViewsAgree"]).isEqualTo(true)
        assertThat(report["rejectionsByReason"]).isEqualTo(mapOf("BLOCKED_ACCOUNT" to 15))
    }

    @Test
    fun `the client is asked only once the gateway knows every result`() {
        val runId = draining()
        bank(runId, summary = summary(runId, posted = 900, rejected = 15))
        clientReconciles(runId)

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("DRAINING")
        assertThat(generator.callsTo("GET", GeneratorApi.RECONCILIATION_PATH.forRun(runId))).isEmpty()
    }

    @Test
    fun `a drain that stops making progress ends incomplete, with how far it got`() {
        val runId = draining()
        bank(runId, books = books(runId, posted = 700, rejected = 10))
        clientReconciles(runId)

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("DRAINING")

        backdate(runId, "drain_progress_at", props.runs.drainStallTimeout)
        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RECONCILED")
        val report = reportOf(runId)
        assertThat(report["result"]).isEqualTo("INCOMPLETE")
        assertThat(report["reasons"]).isEqualTo(listOf("drain timed out: ledger applied 710, gateway results 995, of 995"))
        assertThat(report["bankViewsAgree"]).isNull()
    }

    @Test
    fun `progress in the ledger keeps the drain waiting`() {
        val runId = draining()
        bank(runId, books = books(runId, posted = 700, rejected = 10))
        backdate(runId, "drain_progress_at", props.runs.drainStallTimeout)

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("DRAINING")
        assertThat(column(runId, "drain_progress")).isEqualTo(710L + 995L)
    }

    @Test
    fun `a gateway still catching up on results keeps the drain waiting while the ledger is done`() {
        val runId = draining()
        bank(runId, summary = summary(runId, posted = 900, rejected = 15))
        clientReconciles(runId)
        conductor.tick()
        backdate(runId, "drain_progress_at", props.runs.drainStallTimeout)
        bank(runId, summary = summary(runId, posted = 950, rejected = 15))

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("DRAINING")
    }

    @Test
    fun `more results than accepted transfers end the drain at once as a break`() {
        val runId = draining()
        bank(runId, books = books(runId, posted = 981, rejected = 15))
        clientReconciles(runId)

        conductor.tick()

        val report = reportOf(runId)
        assertThat(report["result"]).isEqualTo("BREAKS")
        assertThat(report["bankViewsAgree"]).isEqualTo(false)
        assertThat(report["reasons"] as List<*>).isEmpty()
    }

    @Test
    fun `a drain past its deadline ends incomplete even while it progresses`() {
        val runId = draining()
        bank(runId, books = books(runId, posted = 700, rejected = 10))
        clientReconciles(runId)
        backdate(runId, "drain_started_at", props.runs.drainTimeout)

        conductor.tick()

        assertThat(column(runId, "result")).isEqualTo("INCOMPLETE")
    }

    @Test
    fun `a lost client leaves the bank's verdict and says the client side is missing`() {
        val runId = draining(clientLost = true)
        bank(runId)

        conductor.tick()

        assertThat(generator.callsTo("GET", GeneratorApi.RECONCILIATION_PATH.forRun(runId))).isEmpty()
        val report = reportOf(runId)
        assertThat(report["result"]).isEqualTo("INCOMPLETE")
        assertThat(report["reasons"]).isEqualTo(listOf("client lost"))
        assertThat(report["bankViewsAgree"]).isEqualTo(true)
    }

    @Test
    fun `a client reconciliation that cannot succeed ends incomplete with the generator's words`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId, 404, mapOf("error" to "journal released"))

        conductor.tick()

        assertThat(reportOf(runId)["reasons"]).isEqualTo(listOf("client reconciliation failed: journal released"))
    }

    @Test
    fun `clients still stopping when the bank has drained are asked again on the next tick`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId, 409, mapOf("error" to GeneratorApi.RUN_STILL_ACTIVE))

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("DRAINING")

        clientReconciles(runId)
        conductor.tick()
        assertThat(column(runId, "result")).isEqualTo("BALANCED")
    }

    @Test
    fun `clients still stopping at the drain deadline end incomplete with the generator's words`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId, 409, mapOf("error" to GeneratorApi.RUN_STILL_ACTIVE))
        backdate(runId, "drain_started_at", props.runs.drainTimeout)

        conductor.tick()

        assertThat(reportOf(runId)["reasons"]).isEqualTo(listOf("client reconciliation failed: run still active"))
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `a reconciled run's journal is released once, and a generator that cannot release it is only logged`(output: CapturedOutput) {
        val released = draining()
        val failing = draining()
        listOf(released, failing).forEach { bank(it); clientReconciles(it) }
        generator.answer("DELETE", GeneratorApi.JOURNAL_PATH.forRun(failing), 500)

        conductor.tick()
        conductor.tick()

        listOf(released, failing).forEach { runId ->
            assertThat(stateOf(runId)).isEqualTo("RECONCILED")
            assertThat(generator.callsTo("DELETE", GeneratorApi.JOURNAL_PATH.forRun(runId))).hasSize(1)
        }
        assertThat(output.out).contains("Run $failing: journal not released").doesNotContain("Run $released: journal not released")
    }

    @Test
    fun `a report stored before the refusal count existed is still read, with none refused`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId)
        conductor.tick()
        jdbc.sql("UPDATE runs SET report = report #- '{client,refused}' WHERE run_id = :id").param("id", runId).update()

        assertThat((reportOf(runId)["client"] as Map<*, *>)["refused"]).isEqualTo(0)
    }

    @Test
    fun `the report carries the transfers the gateway refused as busy, from the client reconciliation`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId, body = clientReport(runId, refused = 7))

        conductor.tick()

        assertThat((reportOf(runId)["client"] as Map<*, *>)["refused"]).isEqualTo(7)
    }

    @Test
    fun `an inconsistent statement ends incomplete at once`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId, 502, mapOf("error" to "statement incomplete"))

        conductor.tick()

        assertThat(reportOf(runId)["reasons"]).isEqualTo(listOf("client reconciliation failed: statement incomplete"))
    }

    @Test
    fun `an unavailable client reconciliation is retried until the drain deadline`() {
        val runId = draining()
        bank(runId)
        clientReconciles(runId, 503, mapOf("error" to "gateway unavailable"))

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("DRAINING")

        backdate(runId, "drain_started_at", props.runs.drainTimeout)
        conductor.tick()
        assertThat(reportOf(runId)["reasons"]).isEqualTo(listOf("client reconciliation unavailable"))
    }

    @Test
    fun `a bank that does not answer until the deadline fails the run without a verdict`() {
        val runId = draining()
        gateway.answer("GET", GatewayApi.SUMMARY_PATH.forRun(runId), 200, summary(runId))
        ledger.answer("GET", LedgerApi.RECONCILIATION_PATH.forRun(runId), 503)

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("DRAINING")

        backdate(runId, "drain_started_at", props.runs.drainTimeout)
        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("FAILED")
        assertThat(column(runId, "failure")).isEqualTo("bank unavailable")
        assertThat(column(runId, "result")).isNull()
    }

    @Test
    fun `a projection behind books that are final keeps the drain waiting, and the report comes once it caught up`() {
        val runId = draining()
        bank(runId, stats = stats(runId, accepted = 990, posted = 975, rejected = 15))
        clientReconciles(runId)

        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("DRAINING")
        assertThat(generator.callsTo("GET", GeneratorApi.RECONCILIATION_PATH.forRun(runId))).isEmpty()

        reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 200, stats(runId))
        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RECONCILED")
        val report = reportOf(runId)
        assertThat(report["result"]).isEqualTo("BALANCED")
        assertThat(report["projectionMatchesBooks"]).isEqualTo(true)
        assertThat(report["notes"] as List<*>).isEmpty()
        assertThat(((report["stats"] as Map<*, *>)["totals"] as Map<*, *>)["accepted"]).isEqualTo(995)
    }

    @Test
    fun `the books are marked final once, when the drain first finds them final, and the mark survives a restart`() {
        val runId = draining()
        bank(runId, stats = stats(runId, accepted = 990, posted = 975, rejected = 15))
        clientReconciles(runId)

        conductor.tick()
        val first = column(runId, "books_final_at")
        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("DRAINING")
        assertThat(first).isNotNull()
        assertThat(column(runId, "books_final_at")).isEqualTo(first)
        val sessionId = jdbc.sql("SELECT session_id FROM runs WHERE run_id = :id").param("id", runId).query(UUID::class.java).single()
        assertThat(java.time.Instant.parse(getRun(sessionId, runId).body!!["booksFinalAt"] as String))
            .isEqualTo((first as java.sql.Timestamp).toInstant())
    }

    @Test
    fun `books not final yet are not marked`() {
        val runId = draining()
        bank(runId, books = books(runId, posted = 900))

        conductor.tick()

        assertThat(column(runId, "books_final_at")).isNull()
    }

    @Test
    fun `results of every transfer without all their commands are not caught up yet, and the verdict stays the books'`() {
        val runId = draining()
        bank(runId, stats = stats(runId, commandsSeen = 990))
        clientReconciles(runId)

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("DRAINING")

        backdate(runId, "drain_progress_at", props.reporting.catchUpWait)
        conductor.tick()

        val report = reportOf(runId)
        assertThat(report["result"]).isEqualTo("BALANCED")
        assertThat(report["reasons"] as List<*>).isEmpty()
        assertThat(report["projectionMatchesBooks"]).isEqualTo(false)
        assertThat(report["notes"]).isEqualTo(listOf("projection does not match the books: commands 990 of 995, posted 980 of 980, rejected 15 of 15"))
    }

    @Test
    fun `a reporting service that does not answer is waited for as long, then noted without stats`() {
        val runId = draining()
        bank(runId, stats = null)
        reporting.status = 503
        clientReconciles(runId)

        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("DRAINING")

        backdate(runId, "drain_progress_at", props.reporting.catchUpWait)
        conductor.tick()

        val report = reportOf(runId)
        assertThat(report["result"]).isEqualTo("BALANCED")
        assertThat(report["stats"]).isNull()
        assertThat(report["projectionMatchesBooks"]).isNull()
        assertThat(report["notes"]).isEqualTo(listOf("reporting unavailable: no numbers from the bank's events"))
    }

    @Test
    fun `a drain that ends without final books does not wait for the projection`() {
        val runId = draining()
        bank(runId, books = books(runId, posted = 700, rejected = 10), stats = null)
        reporting.status = 503
        clientReconciles(runId)
        conductor.tick()

        backdate(runId, "drain_progress_at", props.runs.drainStallTimeout)
        conductor.tick()

        assertThat(stateOf(runId)).isEqualTo("RECONCILED")
        assertThat(reportOf(runId)["notes"]).isEqualTo(listOf("reporting unavailable: no numbers from the bank's events"))
    }

    @Test
    fun `the report carries the clients' exact latency and the ledger's duplicates seen while the run was live`() {
        val runId = draining()
        bank(runId, stats = stats(runId, accepted = 990, posted = 975, rejected = 15))
        clientReconciles(runId)
        generator.answer(
            "GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200,
            simulation(runId, "FINISHED") + mapOf("waitedLatencyP50Ms" to 101.5, "waitedLatencyP99Ms" to 870.0),
        )
        prometheus.answerQuery("GET", PROMETHEUS_QUERY, "query=sum by (run) (ledger_duplicates_total)", 200, duplicates(runId to 7))
        // Live numbers are read beside the steps of the same tick, so the report takes those of an earlier tick.
        conductor.tick()
        prometheus.answerQuery("GET", PROMETHEUS_QUERY, "query=sum by (run) (ledger_duplicates_total)", 200, duplicates(runId to 5))
        reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 200, stats(runId))
        conductor.tick()

        val report = reportOf(runId)
        assertThat(report["clientLatencyP50Ms"]).isEqualTo(101.5)
        assertThat(report["clientLatencyP99Ms"]).isEqualTo(870.0)
        assertThat(report["ledgerDuplicatesAtLeast"]).isEqualTo(7)
    }
}
