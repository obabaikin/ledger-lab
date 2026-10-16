package com.ledgerlab.control

import com.ledgerlab.control.api.ControlErrors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class RunStartTest : IntegrationTest() {

    private fun runsOf(token: String): Long =
        jdbc.sql("SELECT count(*) FROM runs WHERE token = :token").param("token", token).query(Long::class.java).single()

    private fun <T> concurrently(times: Int, call: () -> T): List<T> {
        val gate = CountDownLatch(1)
        return Executors.newFixedThreadPool(times).use { pool ->
            val futures = (1..times).map { pool.submit<T> { gate.await(); call() } }
            gate.countDown()
            futures.map { it.get() }
        }
    }

    @Test
    fun `a start joins the queue with its position and location`() {
        val token = invite()
        val sessionId = sessionOf(token)
        start(sessionOf(token))

        val response = start(sessionId, tps = 500, durationSeconds = 20)

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val run = response.body!!
        assertThat(run["state"]).isEqualTo("QUEUED")
        assertThat(run["position"]).isEqualTo(2)
        assertThat(run["tps"]).isEqualTo(500)
        assertThat(run["durationSeconds"]).isEqualTo(20)
        assertThat(run["admittedAt"]).isNull()
        assertThat(response.headers.location.toString()).isEqualTo("/api/sessions/$sessionId/runs/${run["runId"]}")
        assertThat(getRun(sessionId, run["runId"]).body).isEqualTo(run)
    }

    @Test
    fun `tps and duration outside the lists are refused with the allowed values`() {
        val sessionId = sessionOf(invite())

        val tps = start(sessionId, tps = 200)
        val duration = start(sessionId, durationSeconds = 60)
        val missing = start(sessionId, tps = null)

        assertThat(tps.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(tps.body!!["error"]).isEqualTo("tps must be one of [100, 500, 1000]")
        assertThat(duration.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(duration.body!!["error"]).isEqualTo("durationSeconds must be one of [10, 20, 30]")
        assertThat(missing.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    private fun startWithoutRequestId(sessionId: Any) =
        http.postForEntity("/api/sessions/$sessionId/runs", mapOf("tps" to 100, "durationSeconds" to 10), Map::class.java)

    private fun postRaw(path: String, body: String) =
        http.postForEntity(path, HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }), Map::class.java)

    @Test
    fun `a start without a requestId is refused`() {
        val response = startWithoutRequestId(sessionOf(invite()))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.REQUEST_ID_REQUIRED)
    }

    @Test
    fun `an unknown session is reported before a missing requestId`() {
        val response = startWithoutRequestId(UUID.randomUUID())

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.SESSION_NOT_FOUND)
    }

    @Test
    fun `an unreadable body is 400 with an error body`() {
        val sessionId = sessionOf(invite())

        listOf("/api/sessions", "/api/sessions/$sessionId/runs").forEach { path ->
            val response = postRaw(path, "{not json")
            assertThat(response.statusCode).`as`(path).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body!!["error"]).`as`(path).isEqualTo(ControlErrors.INVALID_REQUEST_BODY)
        }
    }

    @Test
    fun `an id that is not a UUID is 404, as an unknown one`() {
        val sessionId = sessionOf(invite())

        val session = http.getForEntity("/api/sessions/not-a-uuid", Map::class.java)
        val runs = start("not-a-uuid")
        val run = getRun(sessionId, "not-a-uuid")

        assertThat(listOf(session, runs, run).map { it.statusCode }).containsOnly(HttpStatus.NOT_FOUND)
        assertThat(session.body!!["error"]).isEqualTo(ControlErrors.SESSION_NOT_FOUND)
        assertThat(runs.body!!["error"]).isEqualTo(ControlErrors.SESSION_NOT_FOUND)
        assertThat(run.body!!["error"]).isEqualTo(ControlErrors.RUN_NOT_FOUND)
    }

    @Test
    fun `a repeat with the same requestId returns the same run and spends no quota`() {
        val token = invite(quota = 1)
        val sessionId = sessionOf(token)
        val requestId = UUID.randomUUID()

        val first = start(sessionId, requestId)
        val repeat = start(sessionId, requestId)

        assertThat(repeat.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(repeat.body!!["runId"]).isEqualTo(first.body!!["runId"])
        assertThat(runsOf(token)).isEqualTo(1)
    }

    @Test
    fun `a repeat with a different body is 422`() {
        val sessionId = sessionOf(invite())
        val requestId = UUID.randomUUID()
        start(sessionId, requestId)

        val response = start(sessionId, requestId, tps = 500)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.REQUEST_ID_CONFLICT)
    }

    @Test
    fun `a repeat of a final run answers 200`() {
        val sessionId = sessionOf(invite())
        val requestId = UUID.randomUUID()
        val runId = start(sessionId, requestId).body!!["runId"]
        setRun(runId, state = "EXPIRED")

        val repeat = start(sessionId, requestId)

        assertThat(repeat.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(repeat.body!!["state"]).isEqualTo("EXPIRED")
    }

    @Test
    fun `the same requestId in another session is another run`() {
        val token = invite()
        val requestId = UUID.randomUUID()

        val first = start(sessionOf(token), requestId)
        val second = start(sessionOf(token), requestId)

        assertThat(second.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(second.body!!["runId"]).isNotEqualTo(first.body!!["runId"])
    }

    @Test
    fun `the quota counts every start of the invite in the last hour, from any session and in any state, and says when to retry`() {
        val token = invite(quota = 2)
        val expired = start(sessionOf(token)).body!!["runId"]
        setRun(expired, state = "EXPIRED", createdAgo = Duration.ofMinutes(50))
        start(sessionOf(token))

        val response = start(sessionOf(token))

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.RUN_QUOTA_EXCEEDED)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)!!.toLong()).isBetween(595L, 600L)
    }

    @Test
    fun `with a quota lowered below the starts made, Retry-After waits until fewer than the quota remain`() {
        val token = invite(quota = 5)
        listOf(50L, 40L, 30L, 20L).forEach { setRun(start(sessionOf(token)).body!!["runId"], createdAgo = Duration.ofMinutes(it)) }
        jdbc.sql("UPDATE invite_tokens SET quota = 2 WHERE token = :token").param("token", token).update()

        val response = start(sessionOf(token))

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)!!.toLong()).isBetween(1795L, 1800L)
    }

    @Test
    fun `starts older than an hour do not count against the quota`() {
        val token = invite(quota = 1)
        setRun(start(sessionOf(token)).body!!["runId"], createdAgo = Duration.ofMinutes(61))

        assertThat(start(sessionOf(token)).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `a repeat is recognised before the quota`() {
        val token = invite(quota = 1)
        val sessionId = sessionOf(token)
        val requestId = UUID.randomUUID()
        start(sessionId, requestId)

        assertThat(start(sessionId, requestId).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(start(sessionOf(token)).statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `an invite no longer valid is reported before its used-up quota`() {
        val token = invite(quota = 1)
        val other = sessionOf(token)
        start(sessionOf(token))
        jdbc.sql("UPDATE invite_tokens SET active = false WHERE token = :token").param("token", token).update()

        assertThat(start(other).body!!["error"]).isEqualTo(ControlErrors.INVITE_NOT_VALID)
    }

    @Test
    fun `a used-up quota is reported before a full queue`() {
        val token = invite(quota = props.runs.maxQueued)
        repeat(props.runs.maxQueued) { start(sessionOf(token)) }

        val response = start(sessionOf(token))

        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.RUN_QUOTA_EXCEEDED)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNotNull()
    }

    @Test
    fun `a repeat is recognised before the invite is checked`() {
        val token = invite()
        val sessionId = sessionOf(token)
        val requestId = UUID.randomUUID()
        start(sessionId, requestId)
        jdbc.sql("UPDATE invite_tokens SET active = false WHERE token = :token").param("token", token).update()

        assertThat(start(sessionId, requestId).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `a repeat is recognised before the options are checked`() {
        val sessionId = sessionOf(invite())
        val requestId = UUID.randomUUID()
        val runId = start(sessionId, requestId).body!!["runId"]
        jdbc.sql("UPDATE runs SET tps = 200 WHERE run_id = CAST(:runId AS uuid)").param("runId", runId.toString()).update()

        assertThat(start(sessionId, requestId, tps = 200).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `a full queue refuses the next start without Retry-After`() {
        val token = invite()
        repeat(props.runs.maxQueued) { assertThat(start(sessionOf(token)).statusCode).isEqualTo(HttpStatus.ACCEPTED) }

        val response = start(sessionOf(token))

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.RUN_QUEUE_FULL)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNull()
    }

    @Test
    fun `runs that left the queue do not fill it`() {
        val token = invite()
        repeat(props.runs.maxQueued) { setRun(start(sessionOf(token)).body!!["runId"], state = "CREATED") }

        assertThat(start(sessionOf(token)).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `an invite that expired after the session opened starts no run, and the session still reads its runs`() {
        val token = invite()
        val sessionId = sessionOf(token)
        val other = sessionOf(token)
        val runId = start(sessionId).body!!["runId"]
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 second' WHERE token = :token").param("token", token).update()

        val response = start(other)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.INVITE_NOT_VALID)
        assertThat(getRun(sessionId, runId).body!!["state"]).isEqualTo("QUEUED")
    }

    @Test
    fun `a start in an unknown session is 404`() {
        val response = start(UUID.randomUUID())

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.SESSION_NOT_FOUND)
    }

    @Test
    fun `a run of another session is 404, the same as an unknown one`() {
        val token = invite()
        val runId = start(sessionOf(token)).body!!["runId"]
        val other = sessionOf(token)

        val foreign = getRun(other, runId)
        val unknown = getRun(other, UUID.randomUUID())

        assertThat(foreign.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(foreign.body).isEqualTo(unknown.body).isEqualTo(mapOf("error" to ControlErrors.RUN_NOT_FOUND))
    }

    @Test
    fun `a session lists only its runs, newest first`() {
        val token = invite()
        val sessionId = sessionOf(token)
        val older = start(sessionId).body!!["runId"]
        setRun(older, state = "RECONCILED")
        start(sessionOf(token))
        val newer = start(sessionId).body!!["runId"]

        val items = http.getForEntity("/api/sessions/$sessionId/runs", Map::class.java).body!!["items"] as List<*>

        assertThat(items.map { (it as Map<*, *>)["runId"] }).containsExactly(newer, older)
        assertThat(http.getForEntity("/api/sessions/${sessionOf(token)}/runs", Map::class.java).body!!["items"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `concurrent starts from different invites beyond the queue get exactly its free places`() {
        val sessions = List(3 * props.runs.maxQueued) { sessionOf(invite()) }.iterator()

        val statuses = concurrently(3 * props.runs.maxQueued) { start(synchronized(sessions) { sessions.next() }).statusCode }

        assertThat(statuses.count { it == HttpStatus.ACCEPTED }).isEqualTo(props.runs.maxQueued)
        assertThat(statuses.count { it == HttpStatus.TOO_MANY_REQUESTS }).isEqualTo(2 * props.runs.maxQueued)
        val positions = jdbc.sql(
            "SELECT count(*) FROM runs WHERE state = 'QUEUED'",
        ).query(Long::class.java).single()
        assertThat(positions).isEqualTo(props.runs.maxQueued.toLong())
    }

    @Test
    fun `concurrent starts beyond the quota get exactly the quota`() {
        val token = invite(quota = 3)

        val statuses = concurrently(12) { start(sessionOf(token)).statusCode }

        assertThat(statuses.count { it == HttpStatus.ACCEPTED }).isEqualTo(3)
        assertThat(runsOf(token)).isEqualTo(3)
    }

    @Test
    fun `a start while a run of the session is not final is refused, whatever state that run is in`() {
        val sessionId = sessionOf(invite())
        val runId = start(sessionId).body!!["runId"]

        listOf("QUEUED", "CREATED", "RUNNING", "DRAINING").forEach { state ->
            setRun(runId, state = state)
            val response = start(sessionId)
            assertThat(response.statusCode).`as`(state).isEqualTo(HttpStatus.CONFLICT)
            assertThat(response.body!!["error"]).`as`(state).isEqualTo(ControlErrors.RUN_NOT_FINAL)
        }
    }

    @Test
    fun `a repeat of the run that is not final gets that run, not a conflict`() {
        val sessionId = sessionOf(invite())
        val requestId = UUID.randomUUID()
        val runId = start(sessionId, requestId).body!!["runId"]
        setRun(runId, state = "RUNNING")

        val repeat = start(sessionId, requestId)

        assertThat(repeat.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(repeat.body!!["runId"]).isEqualTo(runId)
    }

    @Test
    fun `a final run does not hold back the next start`() {
        val sessionId = sessionOf(invite())

        listOf("RECONCILED", "FAILED", "EXPIRED").forEach { state ->
            setRun(start(sessionId).body!!["runId"], state = state)
            assertThat(start(sessionId).statusCode).`as`(state).isEqualTo(HttpStatus.ACCEPTED)
            jdbc.sql("UPDATE runs SET state = 'EXPIRED' WHERE session_id = :sessionId").param("sessionId", sessionId).update()
        }
    }

    @Test
    fun `a run not final in one session does not hold back another session`() {
        val token = invite()
        start(sessionOf(token))

        assertThat(start(sessionOf(token)).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `concurrent starts in one session make one run, the others are refused`() {
        val token = invite()
        val sessionId = sessionOf(token)

        val statuses = concurrently(8) { start(sessionId).statusCode }

        assertThat(statuses.count { it == HttpStatus.ACCEPTED }).isEqualTo(1)
        assertThat(statuses.count { it == HttpStatus.CONFLICT }).isEqualTo(7)
        assertThat(runsOf(token)).isEqualTo(1)
    }

    @Test
    fun `concurrent repeats of one requestId make one run`() {
        val token = invite()
        val sessionId = sessionOf(token)
        val requestId = UUID.randomUUID()

        val responses: List<ResponseEntity<Map<*, *>>> = concurrently(8) { start(sessionId, requestId) }

        assertThat(responses.map { it.statusCode }).containsOnly(HttpStatus.ACCEPTED)
        assertThat(responses.map { it.body!!["runId"] }.toSet()).hasSize(1)
        assertThat(runsOf(token)).isEqualTo(1)
    }
}
