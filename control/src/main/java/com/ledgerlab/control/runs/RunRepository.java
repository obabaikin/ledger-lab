package com.ledgerlab.control.runs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Times come from clock_timestamp(), taken after the queue lock: now() is the transaction start,
 * which may lie before a wait for the lock and would order runs differently from their admission.
 */
@Repository
public class RunRepository {

    private static final String QUEUE_LOCK = "control.run-queue";
    private static final String SELECT_WITHOUT_POSITION = "SELECT r.*, NULL AS position FROM runs r";
    private static final String SELECT_WITH_POSITION = """
        SELECT r.*,
               CASE WHEN r.state = '%1$s' THEN
                   (SELECT count(*) FROM runs q
                    WHERE q.state = '%1$s' AND (q.created_at, q.run_id) <= (r.created_at, r.run_id))
               END AS position
        FROM runs r""".formatted(RunState.QUEUED.name());

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public RunRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    public void lockQueue() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", QUEUE_LOCK)
            .query(rs -> {
            });
    }

    public @Nullable Run find(UUID sessionId, UUID runId) {
        return oneRun(jdbc.sql(SELECT_WITH_POSITION + " WHERE r.session_id = :sessionId AND r.run_id = :runId")
            .param("sessionId", sessionId)
            .param("runId", runId));
    }

    public @Nullable Run findByRequest(UUID sessionId, UUID requestId) {
        return oneRun(jdbc.sql(SELECT_WITH_POSITION + " WHERE r.session_id = :sessionId AND r.request_id = :requestId")
            .param("sessionId", sessionId)
            .param("requestId", requestId));
    }

    public @Nullable Run findForUpdate(UUID sessionId, UUID runId) {
        return oneRun(jdbc.sql(SELECT_WITHOUT_POSITION + " WHERE r.session_id = :sessionId AND r.run_id = :runId FOR UPDATE")
            .param("sessionId", sessionId)
            .param("runId", runId));
    }

    public boolean hasRun(UUID sessionId, Collection<RunState> states) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM runs WHERE session_id = :sessionId AND state IN (:states))")
            .param("sessionId", sessionId)
            .param("states", names(states))
            .query(Boolean.class)
            .single();
    }

    public List<Run> inProgress() {
        return runs(jdbc.sql(SELECT_WITHOUT_POSITION + " WHERE r.state IN (:states) ORDER BY r.admitted_at, r.run_id")
            .param("states", names(RunState.SLOT_STATES)));
    }

    public @Nullable RunReport reportOf(UUID runId) {
        return jdbc.sql("SELECT report::text FROM runs WHERE run_id = :runId")
            .param("runId", runId)
            .query(String.class)
            .optional()
            .map(it -> objectMapper.readValue(it, RunReport.class))
            .orElse(null);
    }

    public int requestStop(UUID runId) {
        return jdbc.sql("UPDATE runs SET stop_requested_at = coalesce(stop_requested_at, clock_timestamp()) WHERE run_id = :runId AND state = :running")
            .param("runId", runId)
            .param("running", RunState.RUNNING.name())
            .update();
    }

    public Instant markSetupStarted(UUID runId) {
        return jdbc.sql(
            """
            UPDATE runs SET setup_started_at = coalesce(setup_started_at, clock_timestamp())
            WHERE run_id = :runId
            RETURNING setup_started_at
            """)
            .param("runId", runId)
            .query(Timestamp.class)
            .single()
            .toInstant();
    }

    public int markStartSent(UUID runId, boolean sent) {
        return jdbc.sql(
            """
            UPDATE runs SET start_sent_at = CASE WHEN :sent THEN coalesce(start_sent_at, clock_timestamp()) END
            WHERE run_id = :runId AND state = :created
            """)
            .param("sent", sent)
            .param("created", RunState.CREATED.name())
            .param("runId", runId)
            .update();
    }

    public int markStarted(UUID runId) {
        return jdbc.sql("UPDATE runs SET state = :running, started_at = clock_timestamp() WHERE run_id = :runId AND state = :created")
            .param("running", RunState.RUNNING.name())
            .param("created", RunState.CREATED.name())
            .param("runId", runId)
            .update();
    }

    public int startDraining(UUID runId, boolean clientLost) {
        return jdbc.sql(
            """
            UPDATE runs SET state = :draining, drain_started_at = clock_timestamp(), client_lost = :clientLost
            WHERE run_id = :runId AND state = :running
            """)
            .param("draining", RunState.DRAINING.name())
            .param("running", RunState.RUNNING.name())
            .param("clientLost", clientLost)
            .param("runId", runId)
            .update();
    }

    public int markClosed(UUID runId, Instant closedAt) {
        return jdbc.sql(
            """
            UPDATE runs SET closed_at = :closedAt, drain_progress = 0, drain_progress_at = clock_timestamp()
            WHERE run_id = :runId AND state = :draining AND closed_at IS NULL
            """)
            .param("closedAt", Timestamp.from(closedAt))
            .param("draining", RunState.DRAINING.name())
            .param("runId", runId)
            .update();
    }

    public int markBooksFinal(UUID runId) {
        return jdbc.sql("UPDATE runs SET books_final_at = clock_timestamp() WHERE run_id = :runId AND state = :draining AND books_final_at IS NULL")
            .param("draining", RunState.DRAINING.name())
            .param("runId", runId)
            .update();
    }

    public int recordDrainProgress(UUID runId, long progress) {
        return jdbc.sql(
            """
            UPDATE runs SET drain_progress = :progress, drain_progress_at = clock_timestamp()
            WHERE run_id = :runId AND state = :draining AND drain_progress < :progress
            """)
            .param("progress", progress)
            .param("draining", RunState.DRAINING.name())
            .param("runId", runId)
            .update();
    }

    public int markReconciled(UUID runId, RunReport report) {
        return jdbc.sql(
            """
            UPDATE runs SET state = :reconciled, result = :result, report = CAST(:report AS jsonb), finished_at = clock_timestamp()
            WHERE run_id = :runId AND state = :draining
            """)
            .param("reconciled", RunState.RECONCILED.name())
            .param("result", report.result().name())
            .param("report", objectMapper.writeValueAsString(report))
            .param("draining", RunState.DRAINING.name())
            .param("runId", runId)
            .update();
    }

    public int markFailed(UUID runId, RunState from, String failure) {
        return jdbc.sql(
            """
            UPDATE runs SET state = :failed, failure = :failure, finished_at = clock_timestamp()
            WHERE run_id = :runId AND state = :from
            """)
            .param("failed", RunState.FAILED.name())
            .param("failure", failure)
            .param("from", from.name())
            .param("runId", runId)
            .update();
    }

    public List<UUID> purgeable(Duration retention) {
        return jdbc.sql(
            """
            SELECT run_id FROM runs
            WHERE state IN (:states) AND purged_at IS NULL
              AND finished_at <= clock_timestamp() - CAST(:retentionMillis AS bigint) * interval '1 millisecond'
            ORDER BY finished_at
            """)
            .param("states", names(List.of(RunState.RECONCILED, RunState.FAILED)))
            .param("retentionMillis", retention.toMillis())
            .query((rs, rowNum) -> rs.getObject("run_id", UUID.class))
            .list();
    }

    public int markPurged(UUID runId) {
        return jdbc.sql("UPDATE runs SET purged_at = clock_timestamp() WHERE run_id = :runId AND purged_at IS NULL")
            .param("runId", runId)
            .update();
    }

    private static @Nullable Run oneRun(JdbcClient.StatementSpec statement) {
        return statement.query((rs, rowNum) -> toRun(rs)).optional().orElse(null);
    }

    private static List<Run> runs(JdbcClient.StatementSpec statement) {
        return statement.query((rs, rowNum) -> toRun(rs)).list();
    }

    private static List<String> names(Collection<RunState> states) {
        return states.stream().map(Enum::name).toList();
    }

    public List<Run> listOf(UUID sessionId) {
        return runs(jdbc.sql(SELECT_WITH_POSITION + " WHERE r.session_id = :sessionId ORDER BY r.created_at DESC, r.run_id DESC")
            .param("sessionId", sessionId));
    }

    public UUID insertQueued(UUID sessionId, String token, UUID requestId, RunRequest request) {
        var runId = UUID.randomUUID();
        jdbc.sql(
            """
            INSERT INTO runs (run_id, session_id, token, request_id, tps, duration_seconds, state, created_at)
            VALUES (:runId, :sessionId, :token, :requestId, :tps, :durationSeconds, :state, clock_timestamp())
            """)
            .param("runId", runId)
            .param("sessionId", sessionId)
            .param("token", token)
            .param("requestId", requestId)
            .param("tps", request.tps())
            .param("durationSeconds", request.durationSeconds())
            .param("state", RunState.QUEUED.name())
            .update();
        return runId;
    }

    /** The retry time is when fewer than {@code quota} starts remain in the window, so a lowered quota is honoured too. */
    public QuotaUsage quotaUsage(String token, Duration window, int quota) {
        return jdbc.sql(
            """
            WITH started AS (
                SELECT created_at FROM runs
                WHERE token = :token AND created_at > clock_timestamp() - CAST(:windowMillis AS bigint) * interval '1 millisecond'
            )
            SELECT (SELECT count(*) FROM started) AS used,
                   (SELECT ceil(extract(epoch FROM created_at + CAST(:windowMillis AS bigint) * interval '1 millisecond' - clock_timestamp()))
                    FROM started ORDER BY created_at
                    OFFSET greatest((SELECT count(*) FROM started) - :quota, 0) LIMIT 1) AS retry_after
            """)
            .param("token", token)
            .param("windowMillis", window.toMillis())
            .param("quota", quota)
            .query((rs, rowNum) -> new QuotaUsage(rs.getLong("used"), nullableLong(rs, "retry_after")))
            .single();
    }

    public int countIn(Collection<RunState> states) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE state IN (:states)")
            .param("states", names(states))
            .query(Integer.class)
            .single();
    }

    public int expireQueued(Duration timeout) {
        return jdbc.sql(
            """
            UPDATE runs SET state = :expired, finished_at = clock_timestamp()
            WHERE state = :queued AND created_at <= clock_timestamp() - CAST(:timeoutMillis AS bigint) * interval '1 millisecond'
            """)
            .param("expired", RunState.EXPIRED.name())
            .param("queued", RunState.QUEUED.name())
            .param("timeoutMillis", timeout.toMillis())
            .update();
    }

    public int sumTpsIn(Collection<RunState> states) {
        return jdbc.sql("SELECT coalesce(sum(tps), 0) FROM runs WHERE state IN (:states)")
            .param("states", names(states))
            .query(Integer.class)
            .single();
    }

    public int admitOldest(int count, int tpsBudget) {
        return jdbc.sql(
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
            """)
            .param("created", RunState.CREATED.name())
            .param("queued", RunState.QUEUED.name())
            .param("count", count)
            .param("tpsBudget", tpsBudget)
            .update();
    }

    private static Run toRun(ResultSet rs) throws SQLException {
        var result = rs.getString("result");
        return new Run(
            rs.getObject("run_id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getInt("tps"),
            rs.getInt("duration_seconds"),
            RunState.valueOf(rs.getString("state")),
            rs.getObject("created_at", Timestamp.class).toInstant(),
            nullableInstant(rs, "admitted_at"),
            nullableInstant(rs, "finished_at"),
            nullableLong(rs, "position"),
            nullableInstant(rs, "setup_started_at"),
            rs.getObject("start_sent_at") != null,
            nullableInstant(rs, "started_at"),
            rs.getObject("stop_requested_at") != null,
            rs.getBoolean("client_lost"),
            nullableInstant(rs, "drain_started_at"),
            nullableInstant(rs, "closed_at"),
            nullableInstant(rs, "drain_progress_at"),
            nullableInstant(rs, "books_final_at"),
            result == null ? null : RunResult.valueOf(result),
            rs.getString("failure"));
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getObject(column, Timestamp.class);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column) == null ? null : rs.getLong(column);
    }
}
