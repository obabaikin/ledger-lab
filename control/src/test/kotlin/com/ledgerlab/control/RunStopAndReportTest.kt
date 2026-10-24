package com.ledgerlab.control

import com.ledgerlab.control.runs.RunQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class RunStopAndReportTest : IntegrationTest() {

    @Autowired lateinit var queue: RunQueue

    private fun stop(sessionId: Any, runId: Any?) = http.postForEntity("/api/sessions/$sessionId/runs/$runId/stop", null, Map::class.java)

    private fun report(sessionId: Any, runId: Any?) = http.getForEntity("/api/sessions/$sessionId/runs/$runId/report", Map::class.java)

    private fun queuedRun(): Pair<UUID, Any?> {
        val sessionId = sessionOf(invite())
        return sessionId to start(sessionId).body!!["runId"]
    }

    @Test
    fun `a run that has not started cannot be stopped`() {
        val (sessionId, queued) = queuedRun()
        assertThat(stop(sessionId, queued).statusCode.value()).isEqualTo(409)
        assertThat(stop(sessionId, queued).body!!["error"]).isEqualTo("run not started")

        queue.advance(bankReady = true)
        assertThat(stateOf(queued)).isEqualTo("CREATED")
        assertThat(stop(sessionId, queued).statusCode.value()).isEqualTo(409)
    }

    @Test
    fun `a stop of a running run is taken once, a repeat answers the same`() {
        val (sessionId, runId) = queuedRun()
        setRun(runId, state = "RUNNING")

        val first = stop(sessionId, runId)
        val firstAt = jdbc.sql("SELECT stop_requested_at FROM runs WHERE run_id = CAST(:id AS uuid)").param("id", runId.toString()).query(java.sql.Timestamp::class.java).single()
        val repeat = stop(sessionId, runId)

        assertThat(first.statusCode.value()).isEqualTo(202)
        assertThat(repeat.statusCode.value()).isEqualTo(202)
        assertThat(repeat.body!!["stopRequested"]).isEqualTo(true)
        assertThat(jdbc.sql("SELECT stop_requested_at FROM runs WHERE run_id = CAST(:id AS uuid)").param("id", runId.toString()).query(java.sql.Timestamp::class.java).single()).isEqualTo(firstAt)
    }

    @Test
    fun `a stop after the clients ended answers 200 and changes nothing`() {
        val (sessionId, runId) = queuedRun()
        setRun(runId, state = "DRAINING")

        val answer = stop(sessionId, runId)

        assertThat(answer.statusCode.value()).isEqualTo(200)
        assertThat(answer.body!!["stopRequested"]).isEqualTo(false)
    }

    @Test
    fun `stop and report name an unknown session before an unknown run`() {
        val (sessionId, _) = queuedRun()

        assertThat(stop(UUID.randomUUID(), UUID.randomUUID()).body!!["error"]).isEqualTo("session not found")
        assertThat(stop(sessionId, UUID.randomUUID()).statusCode.value()).isEqualTo(404)
        assertThat(stop(sessionId, UUID.randomUUID()).body!!["error"]).isEqualTo("run not found")
        assertThat(report(UUID.randomUUID(), UUID.randomUUID()).body!!["error"]).isEqualTo("session not found")
        assertThat(report(sessionId, UUID.randomUUID()).body!!["error"]).isEqualTo("run not found")
    }

    @Test
    fun `a run of another session cannot be stopped or read`() {
        val (_, runId) = queuedRun()
        val other = sessionOf(invite())
        setRun(runId, state = "RUNNING")

        assertThat(stop(other, runId).statusCode.value()).isEqualTo(404)
        assertThat(report(other, runId).statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `there is no report before the run is reconciled`() {
        val (sessionId, runId) = queuedRun()

        listOf("QUEUED", "RUNNING", "DRAINING", "FAILED").forEach { state ->
            setRun(runId, state = state)
            val answer = report(sessionId, runId)
            assertThat(answer.statusCode.value()).`as`(state).isEqualTo(409)
            assertThat(answer.body!!["error"]).isEqualTo("report not ready")
        }
    }

    @Test
    fun `the chaos button answers that it is not available, and the session says so`() {
        val sessionId = sessionOf(invite())

        val answer = http.postForEntity("/api/sessions/$sessionId/chaos", null, Map::class.java)

        assertThat(answer.statusCode.value()).isEqualTo(501)
        assertThat(answer.body!!["error"]).isEqualTo("chaos not available")
        assertThat(http.getForEntity("/api/sessions/$sessionId", Map::class.java).body!!["chaosAvailable"]).isEqualTo(false)
        assertThat(http.getForEntity("/api/sessions/$sessionId", Map::class.java).body!!["chaosReadyInSeconds"]).isEqualTo(0)
        assertThat(ledgerInstances.flatMap { it.calls }).isEmpty()
        assertThat(http.postForEntity("/api/sessions/${UUID.randomUUID()}/chaos", null, Map::class.java).statusCode.value()).isEqualTo(404)
    }
}
