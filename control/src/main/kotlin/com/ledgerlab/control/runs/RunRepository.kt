package com.ledgerlab.control.runs

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Times come from clock_timestamp(), taken after the queue lock: now() is the transaction start,
 * which may lie before a wait for the lock and would order runs differently from their admission.
 */
@Repository
class RunRepository(private val jdbc: JdbcClient, private val objectMapper: ObjectMapper) {

    fun now(): Instant = jdbc.sql("SELECT clock_timestamp()").query(Timestamp::class.java).single().toInstant()

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
            .oneRun()

    fun findByRequest(sessionId: UUID, requestId: UUID): Run? =
        jdbc.sql("$SELECT_WITH_POSITION WHERE r.session_id = :sessionId AND r.request_id = :requestId")
            .param("sessionId", sessionId)
            .param("requestId", requestId)
            .oneRun()

    fun findForUpdate(sessionId: UUID, runId: UUID): Run? =
        jdbc.sql("$SELECT_WITHOUT_POSITION WHERE r.session_id = :sessionId AND r.run_id = :runId FOR UPDATE")
            .param("sessionId", sessionId)
            .param("runId", runId)
            .oneRun()

    fun hasRun(sessionId: UUID, states: Collection<RunState>): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM runs WHERE session_id = :sessionId AND state IN (:states))")
            .param("sessionId", sessionId)
            .param("states", states.map { it.name })
            .query(Boolean::class.java)
            .single()

    fun inProgress(): List<Run> =
        jdbc.sql("$SELECT_WITHOUT_POSITION WHERE r.state IN (:states) ORDER BY r.admitted_at, r.run_id")
            .param("states", SLOT_STATES.map { it.name })
            .runs()

    fun reportOf(runId: UUID): RunReport? =
        jdbc.sql("SELECT report::text FROM runs WHERE run_id = :runId")
            .param("runId", runId)
            .query(String::class.java)
            .optional()
            .map { objectMapper.readValue(it, RunReport::class.java) }
            .orElse(null)

    fun requestStop(runId: UUID): Int =
        jdbc.sql("UPDATE runs SET stop_requested_at = coalesce(stop_requested_at, clock_timestamp()) WHERE run_id = :runId AND state = :running")
            .param("runId", runId)
            .param("running", RunState.RUNNING.name)
            .update()

    fun markSetupStarted(runId: UUID): Instant =
        jdbc.sql(
            """
            UPDATE runs SET setup_started_at = coalesce(setup_started_at, clock_timestamp())
            WHERE run_id = :runId
            RETURNING setup_started_at
            """,
        )
            .param("runId", runId)
            .query(Timestamp::class.java)
            .single()
            .toInstant()

    fun markStartSent(runId: UUID, sent: Boolean): Int =
        jdbc.sql(
            """
            UPDATE runs SET start_sent_at = CASE WHEN :sent THEN coalesce(start_sent_at, clock_timestamp()) END
            WHERE run_id = :runId AND state = :created
            """,
        )
            .param("sent", sent)
            .param("created", RunState.CREATED.name)
            .param("runId", runId)
            .update()

    fun markStarted(runId: UUID): Int =
        jdbc.sql("UPDATE runs SET state = :running, started_at = clock_timestamp() WHERE run_id = :runId AND state = :created")
            .param("running", RunState.RUNNING.name)
            .param("created", RunState.CREATED.name)
            .param("runId", runId)
            .update()

    fun startDraining(runId: UUID, clientLost: Boolean): Int =
        jdbc.sql(
            """
            UPDATE runs SET state = :draining, drain_started_at = clock_timestamp(), client_lost = :clientLost
            WHERE run_id = :runId AND state = :running
            """,
        )
            .param("draining", RunState.DRAINING.name)
            .param("running", RunState.RUNNING.name)
            .param("clientLost", clientLost)
            .param("runId", runId)
            .update()

    fun markClosed(runId: UUID, closedAt: Instant): Int =
        jdbc.sql(
            """
            UPDATE runs SET closed_at = :closedAt, drain_progress = 0, drain_progress_at = clock_timestamp()
            WHERE run_id = :runId AND state = :draining AND closed_at IS NULL
            """,
        )
            .param("closedAt", Timestamp.from(closedAt))
            .param("draining", RunState.DRAINING.name)
            .param("runId", runId)
            .update()

    fun markBooksFinal(runId: UUID): Int =
        jdbc.sql("UPDATE runs SET books_final_at = clock_timestamp() WHERE run_id = :runId AND state = :draining AND books_final_at IS NULL")
            .param("draining", RunState.DRAINING.name)
            .param("runId", runId)
            .update()

    fun recordDrainProgress(runId: UUID, progress: Long): Int =
        jdbc.sql(
            """
            UPDATE runs SET drain_progress = :progress, drain_progress_at = clock_timestamp()
            WHERE run_id = :runId AND state = :draining AND drain_progress < :progress
            """,
        )
            .param("progress", progress)
            .param("draining", RunState.DRAINING.name)
            .param("runId", runId)
            .update()

    fun markReconciled(runId: UUID, report: RunReport): Int =
        jdbc.sql(
            """
            UPDATE runs SET state = :reconciled, result = :result, report = CAST(:report AS jsonb), finished_at = clock_timestamp()
            WHERE run_id = :runId AND state = :draining
            """,
        )
            .param("reconciled", RunState.RECONCILED.name)
            .param("result", report.result.name)
            .param("report", objectMapper.writeValueAsString(report))
            .param("draining", RunState.DRAINING.name)
            .param("runId", runId)
            .update()

    fun markFailed(runId: UUID, from: RunState, failure: String): Int =
        jdbc.sql(
            """
            UPDATE runs SET state = :failed, failure = :failure, finished_at = clock_timestamp()
            WHERE run_id = :runId AND state = :from
            """,
        )
            .param("failed", RunState.FAILED.name)
            .param("failure", failure)
            .param("from", from.name)
            .param("runId", runId)
            .update()

    fun purgeable(retention: Duration): List<UUID> =
        jdbc.sql(
            """
            SELECT run_id FROM runs
            WHERE state IN (:states) AND purged_at IS NULL
              AND finished_at <= clock_timestamp() - CAST(:retentionMillis AS bigint) * interval '1 millisecond'
            ORDER BY finished_at
            """,
        )
            .param("states", listOf(RunState.RECONCILED, RunState.FAILED).map { it.name })
            .param("retentionMillis", retention.toMillis())
            .query { rs, _ -> rs.getObject("run_id", UUID::class.java) }
            .list()

    fun markPurged(runId: UUID): Int =
        jdbc.sql("UPDATE runs SET purged_at = clock_timestamp() WHERE run_id = :runId AND purged_at IS NULL")
            .param("runId", runId)
            .update()

    private fun JdbcClient.StatementSpec.oneRun(): Run? = query { rs, _ -> rs.toRun() }.optional().orElse(null)

    private fun JdbcClient.StatementSpec.runs(): List<Run> = query { rs, _ -> rs.toRun() }.list()

    fun listOf(sessionId: UUID): List<Run> =
        jdbc.sql("$SELECT_WITH_POSITION WHERE r.session_id = :sessionId ORDER BY r.created_at DESC, r.run_id DESC")
            .param("sessionId", sessionId)
            .runs()

    fun insertQueued(sessionId: UUID, token: String, requestId: UUID, request: RunRequest): UUID {
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
            .param("requestId", requestId)
            .param("tps", request.tps)
            .param("durationSeconds", request.durationSeconds)
            .param("state", RunState.QUEUED.name)
            .update()
        return runId
    }

    /** The retry time is when fewer than [quota] starts remain in the window, so a lowered quota is honoured too. */
    fun quotaUsage(token: String, window: Duration, quota: Int): QuotaUsage =
        jdbc.sql(
            """
            WITH started AS (
                SELECT created_at FROM runs
                WHERE token = :token AND created_at > clock_timestamp() - CAST(:windowMillis AS bigint) * interval '1 millisecond'
            )
            SELECT (SELECT count(*) FROM started) AS used,
                   (SELECT ceil(extract(epoch FROM created_at + CAST(:windowMillis AS bigint) * interval '1 millisecond' - clock_timestamp()))
                    FROM started ORDER BY created_at
                    OFFSET greatest((SELECT count(*) FROM started) - :quota, 0) LIMIT 1) AS retry_after
            """,
        )
            .param("token", token)
            .param("windowMillis", window.toMillis())
            .param("quota", quota)
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

    fun sumTpsIn(states: Collection<RunState>): Int =
        jdbc.sql("SELECT coalesce(sum(tps), 0) FROM runs WHERE state IN (:states)")
            .param("states", states.map { it.name })
            .query(Int::class.java)
            .single()

    fun admitOldest(count: Int, tpsBudget: Int): Int =
        jdbc.sql(
            """
            UPDATE runs SET state = :created, admitted_at = clock_timestamp()
            WHERE run_id IN (
                SELECT run_id FROM (
                    SELECT run_id,
                           row_number() OVER (ORDER BY created_at, run_id) AS place,
                           sum(tps) OVER (ORDER BY created_at, run_id) AS tps_so_far
                    FROM runs WHERE state = :queued
                ) queue
                WHERE place <= :count AND tps_so_far <= :tpsBudget
            )
            """,
        )
            .param("created", RunState.CREATED.name)
            .param("queued", RunState.QUEUED.name)
            .param("count", count)
            .param("tpsBudget", tpsBudget)
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
        setupStartedAt = getObject("setup_started_at", Timestamp::class.java)?.toInstant(),
        startSent = getObject("start_sent_at") != null,
        startedAt = getObject("started_at", Timestamp::class.java)?.toInstant(),
        stopRequested = getObject("stop_requested_at") != null,
        clientLost = getBoolean("client_lost"),
        drainStartedAt = getObject("drain_started_at", Timestamp::class.java)?.toInstant(),
        closedAt = getObject("closed_at", Timestamp::class.java)?.toInstant(),
        drainProgressAt = getObject("drain_progress_at", Timestamp::class.java)?.toInstant(),
        booksFinalAt = getObject("books_final_at", Timestamp::class.java)?.toInstant(),
        result = getString("result")?.let { RunResult.valueOf(it) },
        failure = getString("failure"),
    )

    private companion object {
        const val QUEUE_LOCK = "control.run-queue"
        const val SELECT_WITHOUT_POSITION = "SELECT r.*, NULL AS position FROM runs r"
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
