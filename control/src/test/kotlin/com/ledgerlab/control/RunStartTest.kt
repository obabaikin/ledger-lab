package com.ledgerlab.control

import com.ledgerlab.control.api.ControlErrors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
        val sessionId = sessionOf(invite())
        start(sessionId)

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

    @Test
    fun `a start without a requestId is refused`() {
        val sessionId = sessionOf(invite())

        val response = http.postForEntity("/api/sessions/$sessionId/runs", mapOf("tps" to 100, "durationSeconds" to 10), Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
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
    fun `starts older than an hour do not count against the quota`() {
        val token = invite(quota = 1)
        val sessionId = sessionOf(token)
        setRun(start(sessionId).body!!["runId"], createdAgo = Duration.ofMinutes(61))

        assertThat(start(sessionId).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `a repeat is recognised before the quota`() {
        val sessionId = sessionOf(invite(quota = 1))
        val requestId = UUID.randomUUID()
        start(sessionId, requestId)

        assertThat(start(sessionId, requestId).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(start(sessionId).statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `a full queue refuses the next start without Retry-After`() {
        val sessionId = sessionOf(invite())
        repeat(props.runs.maxQueued) { assertThat(start(sessionId).statusCode).isEqualTo(HttpStatus.ACCEPTED) }

        val response = start(sessionId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.RUN_QUEUE_FULL)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNull()
    }

    @Test
    fun `runs that left the queue do not fill it`() {
        val sessionId = sessionOf(invite())
        repeat(props.runs.maxQueued) { setRun(start(sessionId).body!!["runId"], state = "CREATED") }

        assertThat(start(sessionId).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `an invite that expired after the session opened starts no run, and the session still reads its runs`() {
        val token = invite()
        val sessionId = sessionOf(token)
        val runId = start(sessionId).body!!["runId"]
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 second' WHERE token = :token").param("token", token).update()

        val response = start(sessionId)

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
        start(sessionOf(token))
        val newer = start(sessionId).body!!["runId"]

        val items = http.getForEntity("/api/sessions/$sessionId/runs", Map::class.java).body!!["items"] as List<*>

        assertThat(items.map { (it as Map<*, *>)["runId"] }).containsExactly(newer, older)
        assertThat(http.getForEntity("/api/sessions/${sessionOf(token)}/runs", Map::class.java).body!!["items"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `concurrent starts beyond the queue get exactly its free places`() {
        val sessionId = sessionOf(invite())

        val statuses = concurrently(3 * props.runs.maxQueued) { start(sessionId).statusCode }

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
