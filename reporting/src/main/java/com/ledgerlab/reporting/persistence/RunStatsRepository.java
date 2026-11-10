package com.ledgerlab.reporting.persistence;

import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.reporting.domain.BankLatency;
import com.ledgerlab.reporting.domain.RunStats;
import com.ledgerlab.reporting.domain.SecondStats;
import com.ledgerlab.reporting.domain.StatsTotals;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RunStatsRepository {

    private static final String POSTED = TransferOutcome.POSTED.name();
    private static final String REJECTED = TransferOutcome.REJECTED.name();

    private static final String LATENCY_MS = "extract(epoch FROM processed_at - accepted_at) * 1000";

    private static final String TOTALS_SQL = """
            SELECT now() AS at,
                   count(*) AS accepted,
                   count(accepted_at) AS commands_seen,
                   count(*) FILTER (WHERE outcome = ?) AS posted,
                   count(*) FILTER (WHERE outcome = ?) AS rejected,
                   coalesce(sum(amount_cents) FILTER (WHERE outcome = ?), 0) AS posted_amount,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY %1$s) AS p50,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY %1$s) AS p99
            FROM transfer_stats WHERE run_id = ?
        """.formatted(LATENCY_MS);

    // A transfer whose result came before its command is counted as accepted in the second of its result,
    // so the running "in flight" never goes below zero.
    private static final String SERIES_SQL = """
            WITH run AS (SELECT * FROM transfer_stats WHERE run_id = ?),
            accepted AS (
                SELECT date_trunc('second', coalesce(accepted_at, processed_at)) AS second, count(*) AS accepted
                FROM run GROUP BY 1
            ),
            processed AS (
                SELECT date_trunc('second', processed_at) AS second,
                       count(*) FILTER (WHERE outcome = ?) AS posted,
                       count(*) FILTER (WHERE outcome = ?) AS rejected,
                       coalesce(sum(amount_cents) FILTER (WHERE outcome = ?), 0) AS posted_amount,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY %1$s) AS p50,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY %1$s) AS p99
                FROM run WHERE processed_at IS NOT NULL GROUP BY 1
            )
            SELECT coalesce(a.second, p.second) AS second,
                   coalesce(a.accepted, 0) AS accepted,
                   coalesce(p.posted, 0) AS posted,
                   coalesce(p.rejected, 0) AS rejected,
                   coalesce(p.posted_amount, 0) AS posted_amount,
                   p.p50, p.p99
            FROM accepted a FULL JOIN processed p ON a.second = p.second
            ORDER BY 1
        """.formatted(LATENCY_MS);

    private final JdbcTemplate jdbc;

    public RunStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunStats snapshot(UUID runId) {
        var reasons = new LinkedHashMap<String, Long>();
        jdbc.query(
            "SELECT reject_reason, count(*) FROM transfer_stats WHERE run_id = ? AND outcome = ? GROUP BY reject_reason",
            rs -> {
                reasons.put(rs.getString(1), rs.getLong(2));
            },
            runId, REJECTED);
        var series = jdbc.query(SERIES_SQL, (rs, rowNum) -> secondStats(rs), runId, POSTED, REJECTED, POSTED);
        var snapshot = jdbc.queryForObject(TOTALS_SQL, (rs, rowNum) -> {
            var accepted = rs.getLong("accepted");
            var commandsSeen = rs.getLong("commands_seen");
            var posted = rs.getLong("posted");
            var rejected = rs.getLong("rejected");
            return new RunStats(
                runId,
                rs.getTimestamp("at").toInstant(),
                new StatsTotals(accepted, commandsSeen, posted, rejected, accepted - posted - rejected, rs.getLong("posted_amount"), reasons),
                new BankLatency(nullableDouble(rs, "p50"), nullableDouble(rs, "p99")),
                series);
        }, POSTED, REJECTED, POSTED, runId);
        return Objects.requireNonNull(snapshot, "the totals query returns one row");
    }

    private static SecondStats secondStats(ResultSet rs) throws SQLException {
        return new SecondStats(
            rs.getTimestamp("second").toInstant(),
            rs.getLong("accepted"),
            rs.getLong("posted"),
            rs.getLong("rejected"),
            rs.getLong("posted_amount"),
            nullableDouble(rs, "p50"),
            nullableDouble(rs, "p99"));
    }

    private static @Nullable Double nullableDouble(ResultSet rs, String column) throws SQLException {
        var value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
