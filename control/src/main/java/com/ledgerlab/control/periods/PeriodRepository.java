package com.ledgerlab.control.periods;

import com.ledgerlab.control.gateway.PeriodSummary;
import com.ledgerlab.control.ledger.LedgerReconciliation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PeriodRepository {

    private static final String COLUMNS = """
        period_start, period_end, state, closing_started_at, closed_at, entry_count, drain_progress_at, books_final_at,
        check_requested_at, client_requested_at, verdict, failure, finished_at, accepted, posted, rejected, posted_amount_cents,
        rejections::text AS rejections, next_action_at, gateway_counts::text AS gateway_counts, ledger_counts::text AS ledger_counts""";
    private static final String FINAL_STATES = "('RECONCILED', 'FAILED')";
    private static final TypeReference<Map<String, Long>> COUNTS_BY_REASON = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PeriodRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Opens every period from the last one known up to the current one, so periods missed while control was down are reconciled too. */
    public int openUpTo(Instant now, Period current, Duration length) {
        var lastEnd = jdbc.sql("SELECT max(period_end) FROM periods").query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
        if (lastEnd == null) {
            jdbc.sql("INSERT INTO bank_start (started_at) VALUES (:now) ON CONFLICT DO NOTHING").param("now", Timestamp.from(now)).update();
        }
        // An end off the grid is left by another period length: the periods go on from the current one.
        var from = lastEnd != null && Period.startsOnGrid(lastEnd, length) && lastEnd.isBefore(current.start()) ? lastEnd : current.start();
        return jdbc.sql(
            """
            INSERT INTO periods (period_start, period_end, state)
            SELECT s, s + CAST(:seconds AS bigint) * interval '1 second', :open
            FROM generate_series(CAST(:from AS timestamptz), CAST(:to AS timestamptz), CAST(:seconds AS bigint) * interval '1 second') s
            ON CONFLICT (period_start) DO NOTHING
            """)
            .param("seconds", length.toSeconds())
            .param("open", PeriodState.OPEN.name())
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(current.start()))
            .update();
    }

    public @Nullable BankPeriod oldestUnfinished() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM periods WHERE state NOT IN " + FINAL_STATES + " ORDER BY period_start LIMIT 1")
            .query((rs, rowNum) -> toPeriod(rs))
            .optional()
            .orElse(null);
    }

    public List<Instant> unfinishedStarts() {
        return jdbc.sql("SELECT period_start FROM periods WHERE state NOT IN " + FINAL_STATES + " ORDER BY period_start")
            .query((rs, rowNum) -> rs.getTimestamp("period_start").toInstant())
            .list();
    }

    public @Nullable BankPeriod find(Instant start) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM periods WHERE period_start = :start")
            .param("start", Timestamp.from(start))
            .query((rs, rowNum) -> toPeriod(rs))
            .optional()
            .orElse(null);
    }

    public List<BankPeriod> endedBy(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM periods WHERE period_end <= :now ORDER BY period_start DESC LIMIT :limit")
            .param("now", Timestamp.from(now))
            .param("limit", limit)
            .query((rs, rowNum) -> toPeriod(rs))
            .list();
    }

    public @Nullable PeriodReport reportOf(Instant start) {
        return jdbc.sql("SELECT report::text FROM periods WHERE period_start = :start AND report IS NOT NULL")
            .param("start", Timestamp.from(start))
            .query(String.class)
            .optional()
            .map(it -> objectMapper.readValue(it, PeriodReport.class))
            .orElse(null);
    }

    public @Nullable Instant bankStartedAt() {
        return jdbc.sql("SELECT started_at FROM bank_start").query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
    }

    public PeriodNumbers numbersOfFinished() {
        var reasons = new TreeMap<String, Long>();
        jdbc.sql(
            """
            SELECT r.key AS reason, sum(CAST(r.value AS bigint)) AS count
            FROM periods p, jsonb_each_text(p.rejections) r
            WHERE p.state IN %s GROUP BY r.key
            """.formatted(FINAL_STATES))
            .query(rs -> {
                reasons.put(rs.getString("reason"), rs.getLong("count"));
            });
        return jdbc.sql(
            """
            SELECT coalesce(sum(accepted), 0) AS accepted, coalesce(sum(posted), 0) AS posted, coalesce(sum(rejected), 0) AS rejected,
                   coalesce(sum(posted_amount_cents), 0) AS posted_amount_cents
            FROM periods WHERE state IN %s
            """.formatted(FINAL_STATES))
            .query((rs, rowNum) -> new PeriodNumbers(
                rs.getLong("accepted"), rs.getLong("posted"), rs.getLong("rejected"), rs.getLong("posted_amount_cents"), reasons))
            .single();
    }

    /** Also takes the close: {@code retryAt} is when another try is due if this one does not close the period. */
    public int markClosing(Instant start, Instant now, Instant retryAt) {
        return jdbc.sql(
            """
            UPDATE periods SET state = :closing, closing_started_at = :now, next_action_at = :retryAt
            WHERE period_start = :start AND state = :open
            """)
            .param("closing", PeriodState.CLOSING.name())
            .param("open", PeriodState.OPEN.name())
            .param("now", Timestamp.from(now))
            .param("retryAt", Timestamp.from(retryAt))
            .param("start", Timestamp.from(start))
            .update();
    }

    /**
     * 1 for the one instance of control that takes the period's next action, due by {@code now}; 0 for the others and
     * before it is due. {@code retryAt} is when the action is due again if this one does not move the period on.
     */
    public int takeNextAction(Instant start, PeriodState state, Instant now, Instant retryAt) {
        return jdbc.sql(
            """
            UPDATE periods SET next_action_at = :retryAt
            WHERE period_start = :start AND state = :state AND (next_action_at IS NULL OR next_action_at <= :now)
            """)
            .param("retryAt", Timestamp.from(retryAt))
            .param("start", Timestamp.from(start))
            .param("state", state.name())
            .param("now", Timestamp.from(now))
            .update();
    }

    /** The next count is not compared with the one before: the time between says nothing about the bank. */
    public int restartLimits(Instant start, Instant now) {
        return jdbc.sql(
            """
            UPDATE periods SET closing_started_at = :now, drain_progress = NULL,
                   drain_progress_at = CASE WHEN drain_progress_at IS NOT NULL THEN CAST(:now AS timestamptz) END
            WHERE period_start = :start
            """)
            .param("now", Timestamp.from(now))
            .param("start", Timestamp.from(start))
            .update();
    }

    public int markClosed(Instant start, Instant closedAt, long entryCount, Instant countAt) {
        return jdbc.sql(
            """
            UPDATE periods SET state = :draining, closed_at = :closedAt, entry_count = :entryCount, next_action_at = :countAt
            WHERE period_start = :start AND state = :closing
            """)
            .param("draining", PeriodState.DRAINING.name())
            .param("closing", PeriodState.CLOSING.name())
            .param("closedAt", Timestamp.from(closedAt))
            .param("entryCount", entryCount)
            .param("countAt", Timestamp.from(countAt))
            .param("start", Timestamp.from(start))
            .update();
    }

    /** 1 if the count moved since it was last recorded. */
    public int recordDrainProgress(Instant start, long progress, Instant now) {
        return jdbc.sql(
            """
            UPDATE periods SET drain_progress = :progress, drain_progress_at = :now
            WHERE period_start = :start AND drain_progress IS DISTINCT FROM :progress
            """)
            .param("progress", progress)
            .param("now", Timestamp.from(now))
            .param("start", Timestamp.from(start))
            .update();
    }

    public int markBooksFinal(Instant start, Instant now) {
        return jdbc.sql("UPDATE periods SET books_final_at = :now WHERE period_start = :start AND books_final_at IS NULL")
            .param("now", Timestamp.from(now))
            .param("start", Timestamp.from(start))
            .update();
    }

    public int markCounted(Instant start, PeriodSummary gatewayCounts, LedgerReconciliation ledgerCounts, Instant now, Instant askAt) {
        return jdbc.sql(
            """
            UPDATE periods SET gateway_counts = CAST(:gatewayCounts AS jsonb), ledger_counts = CAST(:ledgerCounts AS jsonb),
                   check_requested_at = :now, client_requested_at = :now, next_action_at = :askAt
            WHERE period_start = :start AND state = :draining
            """)
            .param("gatewayCounts", objectMapper.writeValueAsString(gatewayCounts))
            .param("ledgerCounts", objectMapper.writeValueAsString(ledgerCounts))
            .param("now", Timestamp.from(now))
            .param("askAt", Timestamp.from(askAt))
            .param("start", Timestamp.from(start))
            .param("draining", PeriodState.DRAINING.name())
            .update();
    }

    public int markCheckRequested(Instant start, Instant now) {
        return jdbc.sql("UPDATE periods SET check_requested_at = :now WHERE period_start = :start")
            .param("now", Timestamp.from(now))
            .param("start", Timestamp.from(start))
            .update();
    }

    public int markClientRequested(Instant start, Instant now) {
        return jdbc.sql("UPDATE periods SET client_requested_at = :now WHERE period_start = :start")
            .param("now", Timestamp.from(now))
            .param("start", Timestamp.from(start))
            .update();
    }

    public int markReconciled(Instant start, PeriodReport report, PeriodNumbers numbers, Instant now) {
        return finish(start, PeriodState.DRAINING, PeriodState.RECONCILED, report.verdict(), null, report, numbers, now);
    }

    public int markFailed(Instant start, PeriodState from, PeriodFailure failure, @Nullable PeriodNumbers numbers, Instant now) {
        return finish(start, from, PeriodState.FAILED, null, failure, null, numbers, now);
    }

    private int finish(
        Instant start,
        PeriodState from,
        PeriodState to,
        @Nullable Verdict verdict,
        @Nullable PeriodFailure failure,
        @Nullable PeriodReport report,
        @Nullable PeriodNumbers numbers,
        Instant now
    ) {
        return jdbc.sql(
            """
            UPDATE periods SET state = :to, verdict = :verdict, failure = :failure, report = CAST(:report AS jsonb), finished_at = :now,
                   accepted = :accepted, posted = :posted, rejected = :rejected, posted_amount_cents = :postedAmountCents,
                   rejections = CAST(:rejections AS jsonb)
            WHERE period_start = :start AND state = :from
            """)
            .param("to", to.name())
            .param("from", from.name())
            .param("verdict", verdict == null ? null : verdict.name())
            .param("failure", failure == null ? null : failure.name())
            .param("report", report == null ? null : objectMapper.writeValueAsString(report))
            .param("now", Timestamp.from(now))
            .param("accepted", numbers == null ? null : numbers.accepted())
            .param("posted", numbers == null ? null : numbers.posted())
            .param("rejected", numbers == null ? null : numbers.rejected())
            .param("postedAmountCents", numbers == null ? null : numbers.postedAmountCents())
            .param("rejections", numbers == null ? null : objectMapper.writeValueAsString(numbers.rejectionsByReason()))
            .param("start", Timestamp.from(start))
            .update();
    }

    private BankPeriod toPeriod(ResultSet rs) throws SQLException {
        var verdict = rs.getString("verdict");
        var failure = rs.getString("failure");
        var rejections = rs.getString("rejections");
        var gatewayCounts = rs.getString("gateway_counts");
        var ledgerCounts = rs.getString("ledger_counts");
        var entryCount = rs.getLong("entry_count");
        var entryCountKnown = !rs.wasNull();
        var numbers = rejections == null
            ? null
            : new PeriodNumbers(
                rs.getLong("accepted"), rs.getLong("posted"), rs.getLong("rejected"), rs.getLong("posted_amount_cents"),
                objectMapper.readValue(rejections, COUNTS_BY_REASON));
        return new BankPeriod(
            rs.getTimestamp("period_start").toInstant(),
            rs.getTimestamp("period_end").toInstant(),
            PeriodState.valueOf(rs.getString("state")),
            instantOrNull(rs, "closing_started_at"),
            instantOrNull(rs, "closed_at"),
            entryCountKnown ? entryCount : null,
            instantOrNull(rs, "drain_progress_at"),
            instantOrNull(rs, "books_final_at"),
            instantOrNull(rs, "check_requested_at"),
            instantOrNull(rs, "client_requested_at"),
            verdict == null ? null : Verdict.valueOf(verdict),
            failure == null ? null : PeriodFailure.valueOf(failure),
            instantOrNull(rs, "finished_at"),
            numbers,
            instantOrNull(rs, "next_action_at"),
            gatewayCounts == null ? null : objectMapper.readValue(gatewayCounts, PeriodSummary.class),
            ledgerCounts == null ? null : objectMapper.readValue(ledgerCounts, LedgerReconciliation.class));
    }

    private static @Nullable Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
