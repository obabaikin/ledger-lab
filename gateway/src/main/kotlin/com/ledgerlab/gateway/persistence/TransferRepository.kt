package com.ledgerlab.gateway.persistence

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.gateway.domain.RunSummary
import com.ledgerlab.gateway.domain.StatementLine
import com.ledgerlab.gateway.domain.Transfer
import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.gateway.domain.rejectReasonOf
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class TransferRepository(private val jdbc: JdbcClient) {

    /** Returns the new row, or null when a transfer with this id already exists (the client's idempotency key). */
    fun insertIfAbsent(transferId: UUID, runId: UUID, fromAccount: Int, toAccount: Int, amountCents: Long): Transfer? =
        jdbc.sql(
            """
            INSERT INTO transfers (transfer_id, run_id, from_account, to_account, amount_cents, status)
            VALUES (:id, :runId, :from, :to, :amount, :status)
            ON CONFLICT (transfer_id) DO NOTHING
            RETURNING *
            """,
        )
            .param("id", transferId)
            .param("runId", runId)
            .param("from", fromAccount)
            .param("to", toAccount)
            .param("amount", amountCents)
            .param("status", TransferStatus.ACCEPTED.name)
            .query { rs, _ -> rs.toTransfer() }
            .optional()
            .orElse(null)

    fun find(transferId: UUID): Transfer? =
        jdbc.sql("SELECT * FROM transfers WHERE transfer_id = :id")
            .param("id", transferId)
            .query { rs, _ -> rs.toTransfer() }
            .optional()
            .orElse(null)

    fun claimUnpublished(limit: Int): List<TransferCommand> =
        jdbc.sql(
            """
            SELECT transfer_id, run_id, from_account, to_account, amount_cents, accepted_at
            FROM transfers
            WHERE published_at IS NULL
            ORDER BY accepted_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
        )
            .param("limit", limit)
            .query { rs, _ ->
                TransferCommand(
                    runId = rs.getObject("run_id", UUID::class.java),
                    transferId = rs.getObject("transfer_id", UUID::class.java),
                    fromAccount = rs.getInt("from_account"),
                    toAccount = rs.getInt("to_account"),
                    amountCents = rs.getLong("amount_cents"),
                    createdAt = rs.getObject("accepted_at", Timestamp::class.java).toInstant(),
                )
            }
            .list()
            .requireNoNulls()

    /** The result may already have arrived; never move a final status back to PUBLISHED. */
    fun markPublished(transferIds: List<UUID>) {
        if (transferIds.isEmpty()) return
        jdbc.sql(
            """
            UPDATE transfers
            SET published_at = now(),
                status = CASE WHEN status = :accepted THEN :published ELSE status END
            WHERE transfer_id = ANY(CAST(:ids AS uuid[]))
            """,
        )
            .param("accepted", TransferStatus.ACCEPTED.name)
            .param("published", TransferStatus.PUBLISHED.name)
            .param("ids", transferIds.map { it.toString() }.toTypedArray())
            .update()
    }

    /** Idempotent: a result applies once; a repeated event finds the row already final. */
    fun applyResult(transferId: UUID, outcome: TransferOutcome, rejectReason: String?): Transfer? =
        jdbc.sql(
            """
            UPDATE transfers
            SET status = :status, reject_reason = :reason, resulted_at = now()
            WHERE transfer_id = :id AND status IN (:accepted, :published)
            RETURNING *
            """,
        )
            .param("status", outcome.name)
            .param("reason", rejectReason)
            .param("id", transferId)
            .param("accepted", TransferStatus.ACCEPTED.name)
            .param("published", TransferStatus.PUBLISHED.name)
            .query { rs, _ -> rs.toTransfer() }
            .optional()
            .orElse(null)

    fun summarize(runId: UUID): RunSummary {
        val totals = jdbc.sql(
            """
            SELECT count(*) AS accepted,
                   count(published_at) AS published,
                   count(*) FILTER (WHERE status = :posted) AS posted,
                   count(*) FILTER (WHERE status = :rejected) AS rejected,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (resulted_at - accepted_at)) * 1000) AS p50,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM (resulted_at - accepted_at)) * 1000) AS p99
            FROM transfers WHERE run_id = :runId
            """,
        )
            .param("runId", runId)
            .param("posted", TransferStatus.POSTED.name)
            .param("rejected", TransferStatus.REJECTED.name)
            .query().singleRow()
        val reasons = jdbc.sql(
            "SELECT reject_reason, count(*) AS n FROM transfers WHERE run_id = :runId AND status = :rejected GROUP BY reject_reason",
        )
            .param("runId", runId)
            .param("rejected", TransferStatus.REJECTED.name)
            .query().listOfRows()
            .associate { it["reject_reason"] as String to (it["n"] as Number).toLong() }
        return RunSummary(
            runId = runId,
            accepted = (totals["accepted"] as Number).toLong(),
            published = (totals["published"] as Number).toLong(),
            posted = (totals["posted"] as Number).toLong(),
            rejected = (totals["rejected"] as Number).toLong(),
            rejectionsByReason = reasons,
            latencyP50Ms = (totals["p50"] as Number?)?.toDouble(),
            latencyP99Ms = (totals["p99"] as Number?)?.toDouble(),
        )
    }

    fun belongsTo(transferId: UUID, runId: UUID): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM transfers WHERE transfer_id = :id AND run_id = :runId)")
            .param("id", transferId)
            .param("runId", runId)
            .query(Boolean::class.java)
            .single()

    /** Keyset page in (accepted_at, transfer_id) order, starting after the transfer [after]. */
    fun statementPage(runId: UUID, after: UUID?, limit: Int): List<StatementLine> {
        val afterClause = if (after == null) "" else
            "AND (accepted_at, transfer_id) > (SELECT accepted_at, transfer_id FROM transfers WHERE transfer_id = :after)"
        var query = jdbc.sql(
            """
            SELECT transfer_id, from_account, to_account, amount_cents, status, reject_reason, accepted_at, resulted_at
            FROM transfers
            WHERE run_id = :runId $afterClause
            ORDER BY accepted_at, transfer_id
            LIMIT :limit
            """,
        )
            .param("runId", runId)
            .param("limit", limit)
        if (after != null) query = query.param("after", after)
        return query.query { rs, _ ->
            StatementLine(
                transferId = rs.getObject("transfer_id", UUID::class.java),
                fromAccount = rs.getInt("from_account"),
                toAccount = rs.getInt("to_account"),
                amountCents = rs.getLong("amount_cents"),
                status = TransferStatus.valueOf(rs.getString("status")),
                rejectReason = rs.getString("reject_reason")?.let(::rejectReasonOf),
                acceptedAt = rs.getObject("accepted_at", Timestamp::class.java).toInstant(),
                resultedAt = rs.getObject("resulted_at", Timestamp::class.java)?.toInstant(),
            )
        }.list()
    }

    fun deleteRun(runId: UUID): Int =
        jdbc.sql("DELETE FROM transfers WHERE run_id = :runId").param("runId", runId).update()

    private fun java.sql.ResultSet.toTransfer() = Transfer(
        transferId = getObject("transfer_id", UUID::class.java),
        runId = getObject("run_id", UUID::class.java),
        fromAccount = getInt("from_account"),
        toAccount = getInt("to_account"),
        amountCents = getLong("amount_cents"),
        status = TransferStatus.valueOf(getString("status")),
        rejectReason = getString("reject_reason")?.let(::rejectReasonOf),
        acceptedAt = getObject("accepted_at", Timestamp::class.java).toInstant(),
        publishedAt = getObject("published_at", Timestamp::class.java)?.toInstant(),
        resultedAt = getObject("resulted_at", Timestamp::class.java)?.toInstant(),
    )
}
