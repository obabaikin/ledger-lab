package com.ledgerlab.reporting.persistence

import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.reporting.domain.BankLatency
import com.ledgerlab.reporting.domain.RunStats
import com.ledgerlab.reporting.domain.SecondStats
import com.ledgerlab.reporting.domain.StatsTotals
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class RunStatsRepository(private val jdbc: JdbcTemplate) {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun snapshot(runId: UUID): RunStats {
        val reasons = jdbc.query(
            "SELECT reject_reason, count(*) FROM transfer_stats WHERE run_id = ? AND outcome = ? GROUP BY reject_reason",
            { rs, _ -> rs.getString(1) to rs.getLong(2) },
            runId, REJECTED,
        ).toMap()
        val series = jdbc.query(SERIES_SQL, { rs, _ -> secondStats(rs) }, runId, POSTED, REJECTED, POSTED)
        return jdbc.queryForObject(TOTALS_SQL, { rs, _ ->
            val accepted = rs.getLong("accepted")
            val commandsSeen = rs.getLong("commands_seen")
            val posted = rs.getLong("posted")
            val rejected = rs.getLong("rejected")
            RunStats(
                runId = runId,
                at = rs.getTimestamp("at").toInstant(),
                totals = StatsTotals(accepted, commandsSeen, posted, rejected, accepted - posted - rejected, rs.getLong("posted_amount"), reasons),
                bankLatency = BankLatency(rs.nullableDouble("p50"), rs.nullableDouble("p99")),
                series = series,
            )
        }, POSTED, REJECTED, POSTED, runId)!!
    }

    private fun secondStats(rs: ResultSet) = SecondStats(
        second = rs.getTimestamp("second").toInstant(),
        accepted = rs.getLong("accepted"),
        posted = rs.getLong("posted"),
        rejected = rs.getLong("rejected"),
        postedAmountCents = rs.getLong("posted_amount"),
        bankP50Ms = rs.nullableDouble("p50"),
        bankP99Ms = rs.nullableDouble("p99"),
    )

    private fun ResultSet.nullableDouble(column: String): Double? = getDouble(column).takeUnless { wasNull() }

    private companion object {
        val POSTED = TransferOutcome.POSTED.name
        val REJECTED = TransferOutcome.REJECTED.name

        const val LATENCY_MS = "extract(epoch FROM processed_at - accepted_at) * 1000"

        const val TOTALS_SQL = """
            SELECT now() AS at,
                   count(*) AS accepted,
                   count(accepted_at) AS commands_seen,
                   count(*) FILTER (WHERE outcome = ?) AS posted,
                   count(*) FILTER (WHERE outcome = ?) AS rejected,
                   coalesce(sum(amount_cents) FILTER (WHERE outcome = ?), 0) AS posted_amount,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY $LATENCY_MS) AS p50,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY $LATENCY_MS) AS p99
            FROM transfer_stats WHERE run_id = ?
        """

        // A transfer whose result came before its command is counted as accepted in the second of its result,
        // so the running "in flight" never goes below zero.
        const val SERIES_SQL = """
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
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY $LATENCY_MS) AS p50,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY $LATENCY_MS) AS p99
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
        """
    }
}
