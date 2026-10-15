package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.books
import com.ledgerlab.control.RunFixtures.clientReport
import com.ledgerlab.control.RunFixtures.stats
import com.ledgerlab.control.RunFixtures.summary
import com.ledgerlab.control.gateway.GatewayApi
import com.ledgerlab.control.generator.GeneratorApi
import com.ledgerlab.control.http.forRun
import com.ledgerlab.control.ledger.LedgerApi
import com.ledgerlab.control.reporting.ReportingApi
import com.ledgerlab.control.runs.Conductor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@TestPropertySource(properties = ["control.chaos.enabled=true", "control.chaos.request-timeout=300ms"])
class ChaosTest : IntegrationTest() {

    @Autowired lateinit var conductor: Conductor

    private fun chaos(sessionId: Any): ResponseEntity<Map<*, *>> = http.postForEntity("/api/sessions/$sessionId/chaos", null, Map::class.java)

    private fun session(sessionId: UUID): Map<*, *> = http.getForEntity("/api/sessions/$sessionId", Map::class.java).body!!

    private fun runIn(sessionId: UUID, state: String): UUID {
        val runId = UUID.fromString(start(sessionId).body!!["runId"] as String)
        jdbc.sql(
            """
            UPDATE runs SET state = :state, admitted_at = clock_timestamp(),
                started_at = CASE WHEN :state <> 'QUEUED' THEN clock_timestamp() END
            WHERE run_id = :id
            """,
        ).param("state", state).param("id", runId).update()
        return runId
    }

    private fun sessionWithRunning(): UUID = sessionOf(invite()).also { runIn(it, "RUNNING") }

    private fun crashCalls() = ledgerInstances.map { it.callsTo("POST", CRASH_PATH).size }

    private fun events(): List<Map<String, Any?>> =
        jdbc.sql("SELECT at, array_to_string(instances, ',') AS instances FROM chaos_events ORDER BY at").query().listOfRows()

    private fun backdateChaos(by: Duration) {
        jdbc.sql("UPDATE chaos_events SET at = at - CAST(:millis AS bigint) * interval '1 millisecond'").param("millis", by.toMillis()).update()
    }

    @Test
    fun `chaos crashes every ledger instance and lists them`() {
        val answer = chaos(sessionWithRunning())

        assertThat(answer.statusCode.value()).isEqualTo(202)
        assertThat(answer.body!!["instances"]).isEqualTo(listOf("ledger-0", "ledger-1", "ledger-2"))
        assertThat(answer.body!!["at"]).isNotNull()
        assertThat(crashCalls()).containsExactly(1, 1, 1)
        assertThat(events().single()["instances"]).isEqualTo("ledger-0,ledger-1,ledger-2")
    }

    @Test
    fun `instances that did not answer are left out of the answer`() {
        ledgerInstances[1].status = 500
        ledgerInstances[2].delay = Duration.ofMillis(800)

        val answer = chaos(sessionWithRunning())

        assertThat(answer.statusCode.value()).isEqualTo(202)
        assertThat(answer.body!!["instances"]).isEqualTo(listOf("ledger-0"))
        assertThat(events().single()["instances"]).isEqualTo("ledger-0")
    }

    @Test
    fun `chaos answers 503 when no instance answered, and the minute is not used`() {
        ledgerInstances.forEach { it.status = 503 }
        val sessionId = sessionWithRunning()

        val refused = chaos(sessionId)

        assertThat(refused.statusCode.value()).isEqualTo(503)
        assertThat(refused.body!!["error"]).isEqualTo("ledger did not answer")
        assertThat(events()).isEmpty()
        assertThat(session(sessionId)["chaosReadyInSeconds"]).isEqualTo(0)
        ledgerInstances.forEach { it.status = 200 }
        assertThat(chaos(sessionId).statusCode.value()).isEqualTo(202)
    }

    @Test
    fun `chaos for an unknown session is 404`() {
        val answer = chaos(UUID.randomUUID())

        assertThat(answer.statusCode.value()).isEqualTo(404)
        assertThat(answer.body!!["error"]).isEqualTo("session not found")
        assertThat(crashCalls()).containsExactly(0, 0, 0)
    }

    @Test
    fun `chaos without a running run of this session is 409`() {
        val sessionId = sessionOf(invite())
        val other = sessionWithRunning()
        assertThat(chaos(sessionId).statusCode.value()).`as`("no run").isEqualTo(409)

        listOf("QUEUED", "CREATED", "DRAINING").forEach { state ->
            val runId = runIn(sessionId, state)
            val answer = chaos(sessionId)
            assertThat(answer.statusCode.value()).`as`(state).isEqualTo(409)
            assertThat(answer.body!!["error"]).isEqualTo("no running run")
            jdbc.sql("DELETE FROM runs WHERE run_id = :id").param("id", runId).update()
        }
        assertThat(crashCalls()).containsExactly(0, 0, 0)
        assertThat(chaos(other).statusCode.value()).isEqualTo(202)
    }

    @Test
    fun `a stopped run is draining, so chaos after the stop is 409`() {
        val sessionId = sessionOf(invite())
        val runId = runIn(sessionId, "RUNNING")
        assertThat(http.postForEntity("/api/sessions/$sessionId/runs/$runId/stop", null, Map::class.java).statusCode.value()).isEqualTo(202)
        jdbc.sql("UPDATE runs SET state = 'DRAINING' WHERE run_id = :id").param("id", runId).update()

        assertThat(chaos(sessionId).statusCode.value()).isEqualTo(409)
    }

    @Test
    fun `a second chaos within the minute is 429 with Retry-After of the time left`() {
        val sessionId = sessionWithRunning()
        assertThat(chaos(sessionId).statusCode.value()).isEqualTo(202)

        val again = chaos(sessionWithRunning())
        assertThat(again.statusCode.value()).isEqualTo(429)
        assertThat(again.body!!["error"]).isEqualTo("chaos used recently")
        assertThat(again.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60")

        backdateChaos(Duration.ofMillis(30_500))
        assertThat(chaos(sessionId).headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30")
        assertThat(crashCalls()).containsExactly(1, 1, 1)
        assertThat(events()).hasSize(1)
    }

    @Test
    fun `of two simultaneous chaos requests exactly one crashes the ledger`() {
        ledgerInstances.forEach { it.delay = Duration.ofMillis(200) }
        val sessions = listOf(sessionWithRunning(), sessionWithRunning())
        val ready = CountDownLatch(sessions.size)

        val answers = Executors.newVirtualThreadPerTaskExecutor().use { presses ->
            sessions.map { sessionId -> presses.submit(Callable { ready.countDown(); ready.await(); chaos(sessionId) }) }.map { it.get() }
        }

        assertThat(answers.map { it.statusCode.value() }).containsExactlyInAnyOrder(202, 429)
        val refused = answers.single { it.statusCode.value() == 429 }
        assertThat(refused.headers.getFirst(HttpHeaders.RETRY_AFTER)!!.toInt()).isBetween(1, 60)
        assertThat(crashCalls()).containsExactly(1, 1, 1)
        assertThat(events()).hasSize(1)
    }

    @Test
    fun `the session counts down to the next chaos and reads 0 once the minute has passed`() {
        val sessionId = sessionWithRunning()
        assertThat(session(sessionId)["chaosAvailable"]).isEqualTo(true)
        assertThat(session(sessionId)["chaosReadyInSeconds"]).isEqualTo(0)

        chaos(sessionId)
        assertThat(session(sessionId)["chaosReadyInSeconds"]).isEqualTo(60)
        backdateChaos(Duration.ofMillis(45_200))
        assertThat(session(sessionId)["chaosReadyInSeconds"]).isEqualTo(15)
        backdateChaos(Duration.ofSeconds(15))
        assertThat(session(sessionId)["chaosReadyInSeconds"]).isEqualTo(0)
        assertThat(chaos(sessionId).statusCode.value()).isEqualTo(202)
    }

    @Test
    fun `chaosReadyInSeconds equals Retry-After of a refused chaos`() {
        val sessionId = sessionWithRunning()
        chaos(sessionId)
        backdateChaos(Duration.ofMillis(12_345))

        val retryAfter = chaos(sessionId).headers.getFirst(HttpHeaders.RETRY_AFTER)

        assertThat(session(sessionId)["chaosReadyInSeconds"].toString()).isEqualTo(retryAfter)
    }

    @Test
    fun `the report of every run active at the crash lists its offset, a run started after it lists none`() {
        val pressing = sessionOf(invite())
        val hit = runIn(pressing, "RUNNING")
        val otherSession = sessionOf(invite())
        val hitInOtherSession = runIn(otherSession, "RUNNING")
        val at = chaos(pressing).body!!["at"]
        jdbc.sql("UPDATE runs SET started_at = CAST(:at AS timestamptz) - interval '10 seconds' WHERE run_id = :id")
            .param("at", at).param("id", hit).update()
        val laterSession = sessionOf(invite())
        val later = runIn(laterSession, "RUNNING")

        listOf(hit, hitInOtherSession, later).forEach { drainAndReconcile(it) }

        assertThat(reportOf(pressing, hit)["chaos"]).isEqualTo(listOf(mapOf("offsetMs" to 10_000, "instances" to listOf("ledger-0", "ledger-1", "ledger-2"))))
        assertThat((reportOf(otherSession, hitInOtherSession)["chaos"] as List<*>)).hasSize(1)
        assertThat(reportOf(laterSession, later)["chaos"]).isEqualTo(emptyList<Any>())
    }

    private fun drainAndReconcile(runId: UUID) {
        jdbc.sql(
            """
            UPDATE runs SET state = 'DRAINING', drain_started_at = clock_timestamp(), closed_at = clock_timestamp(),
                drain_progress = 0, drain_progress_at = clock_timestamp()
            WHERE run_id = :id
            """,
        ).param("id", runId).update()
        gateway.answer("GET", GatewayApi.SUMMARY_PATH.forRun(runId), 200, summary(runId))
        ledger.answer("GET", LedgerApi.RECONCILIATION_PATH.forRun(runId), 200, books(runId))
        reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 200, stats(runId))
        generator.answer("GET", GeneratorApi.RECONCILIATION_PATH.forRun(runId), 200, clientReport(runId))
        conductor.tick()
        assertThat(stateOf(runId)).isEqualTo("RECONCILED")
    }

    private fun reportOf(sessionId: UUID, runId: UUID): Map<*, *> =
        http.getForEntity("/api/sessions/$sessionId/runs/$runId/report", Map::class.java).body!!

    private companion object {
        const val CRASH_PATH = "/actuator/crash"
    }
}
