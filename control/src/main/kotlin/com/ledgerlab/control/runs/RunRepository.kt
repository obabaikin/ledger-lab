package com.ledgerlab.control.runs

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID

/**
 * Times come from clock_timestamp(), taken after the queue lock: now() is the transaction start,
 * which may lie before a wait for the lock and would order runs differently from their admission.
 */
@Repository
class RunRepository(private val jdbc: JdbcClient) {

    fun lockQueue() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", QUEUE_LOCK)
            .query { _, _ -> }
            .single()
    }

    fun find(sessionId: UUID, runId: UUID): Run? =
        jdbc.sql("$SELECT_WITH_POSITION WHERE r.session_id = :sessionId AND r.run_id = :runId")
            .param("sessionId", sessionId)
            .param("runId", runId)
            .query { rs, _ -> rs.toRun() }
            .optional()
            .orElse(null)

    fun findByRequest(sessionId: UUID, requestId: UUID): Run? =
        jdbc.sql("$SELECT_WITH_POSITION WHERE r.session_id = :sessionId AND r.request_id = :requestId")
            .param("sessionId", sessionId)
            .param("requestId", requestId)
            .query { rs, _ -> rs.toRun() }
            .optional()
            .orElse(null)

    fun listOf(sessionId: UUID): List<Run> =
        jdbc.sql("$SELECT_WITH_POSITION WHERE r.session_id = :sessionId ORDER BY r.created_at DESC, r.run_id DESC")
            .param("sessionId", sessionId)
            .query { rs, _ -> rs.toRun() }
            .list()

    fun insertQueued(sessionId: UUID, token: String, request: RunRequest): UUID {
        val runId = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO runs (run_id, session_id, token, request_id, tps, duration_seconds, state, created_at)
            VALUES (:runId, :sessionId, :token, :requestId, :tps, :durationSeconds, :state, clock_timestamp())
            """,
        )
            .param("runId", runId)
            .param("sessionId", sessionId)
            .param("token", token)
            .param("requestId", request.requestId)
            .param("tps", request.tps)
            .param("durationSeconds", request.durationSeconds)
            .param("state", RunState.QUEUED.name)
            .update()
        return runId
    }

    fun quotaUsage(token: String, window: Duration): QuotaUsage =
        jdbc.sql(
            """
            SELECT count(*) AS used,
                   ceil(extract(epoch FROM min(created_at) + CAST(:windowMillis AS bigint) * interval '1 millisecond' - clock_timestamp())) AS retry_after
            FROM runs
            WHERE token = :token AND created_at > clock_timestamp() - CAST(:windowMillis AS bigint) * interval '1 millisecond'
            """,
        )
            .param("token", token)
            .param("windowMillis", window.toMillis())
            .query { rs, _ -> QuotaUsage(rs.getLong("used"), rs.getObject("retry_after")?.let { rs.getLong("retry_after") }) }
            .single()

    fun countIn(states: Collection<RunState>): Int =
        jdbc.sql("SELECT count(*) FROM runs WHERE state IN (:states)")
            .param("states", states.map { it.name })
            .query(Int::class.java)
            .single()

    fun expireQueued(timeout: Duration): Int =
        jdbc.sql(
            """
            UPDATE runs SET state = :expired, finished_at = clock_timestamp()
            WHERE state = :queued AND created_at <= clock_timestamp() - CAST(:timeoutMillis AS bigint) * interval '1 millisecond'
            """,
        )
            .param("expired", RunState.EXPIRED.name)
            .param("queued", RunState.QUEUED.name)
            .param("timeoutMillis", timeout.toMillis())
            .update()

    fun admitOldest(count: Int): Int =
        jdbc.sql(
            """
            UPDATE runs SET state = :created, admitted_at = clock_timestamp()
            WHERE run_id IN (SELECT run_id FROM runs WHERE state = :queued ORDER BY created_at, run_id LIMIT :count)
            """,
        )
            .param("created", RunState.CREATED.name)
            .param("queued", RunState.QUEUED.name)
            .param("count", count)
            .update()

    private fun ResultSet.toRun() = Run(
        runId = getObject("run_id", UUID::class.java),
        sessionId = getObject("session_id", UUID::class.java),
        requestId = getObject("request_id", UUID::class.java),
        tps = getInt("tps"),
        durationSeconds = getInt("duration_seconds"),
        state = RunState.valueOf(getString("state")),
        createdAt = getObject("created_at", Timestamp::class.java).toInstant(),
        admittedAt = getObject("admitted_at", Timestamp::class.java)?.toInstant(),
        finishedAt = getObject("finished_at", Timestamp::class.java)?.toInstant(),
        position = getObject("position")?.let { getLong("position") },
    )

    private companion object {
        const val QUEUE_LOCK = "control.run-queue"
        val SELECT_WITH_POSITION = """
            SELECT r.*,
                   CASE WHEN r.state = '${RunState.QUEUED.name}' THEN
                       (SELECT count(*) FROM runs q
                        WHERE q.state = '${RunState.QUEUED.name}' AND (q.created_at, q.run_id) <= (r.created_at, r.run_id))
                   END AS position
            FROM runs r
        """.trimIndent()
    }
}
