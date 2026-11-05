package com.ledgerlab.gateway.persistence

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.gateway.domain.RunSummary
import com.ledgerlab.gateway.domain.StatementLine
import com.ledgerlab.gateway.domain.Transfer
import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.gateway.domain.rejectReasonOf
import com.ledgerlab.gateway.service.LedgerResult
import com.ledgerlab.gateway.service.TransferRequest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class TransferRepository(private val jdbc: JdbcClient) {

    /**
     * One statement for many requests; returns only the rows it inserted, so a missing id is a key already taken.
     * Keys go in one order in every batch: two batches holding the same repeated keys in opposite orders each wait
     * for the other's uncommitted insert, and Postgres breaks the deadlock by failing one whole batch.
     */
    fun insertAllIfAbsent(requests: List<TransferRequest>): List<Transfer> {
        if (requests.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            INSERT INTO transfers (transfer_id, run_id, from_account, to_account, amount_cents, status)
            SELECT v.transfer_id, v.run_id, v.from_account, v.to_account, v.amount_cents, :status
            FROM unnest(CAST(:ids AS uuid[]), CAST(:runIds AS uuid[]), CAST(:froms AS int[]), CAST(:tos AS int[]), CAST(:amounts AS bigint[]))
                AS v(transfer_id, run_id, from_account, to_account, amount_cents)
            ORDER BY v.transfer_id
            ON CONFLICT (transfer_id) DO NOTHING
            RETURNING *
            """,
        )
            .param("ids", requests.map { it.transferId.toString() }.toTypedArray())
            .param("runIds", requests.map { it.runId.toString() }.toTypedArray())
            .param("froms", requests.map { it.fromAccount }.toTypedArray())
            .param("tos", requests.map { it.toAccount }.toTypedArray())
            .param("amounts", requests.map { it.amountCents }.toTypedArray())
            .param("status", TransferStatus.ACCEPTED.name)
            .query { rs, _ -> rs.toTransfer() }
            .list()
    }

    fun findAll(transferIds: Set<UUID>): List<Transfer> {
        if (transferIds.isEmpty()) return emptyList()
        return jdbc.sql("SELECT * FROM transfers WHERE transfer_id = ANY(CAST(:ids AS uuid[]))")
            .param("ids", transferIds.map { it.toString() }.toTypedArray())
            .query { rs, _ -> rs.toTransfer() }
            .list()
    }

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
    /** One statement for a poll of result events; a repeated event finds its row already final. Returns the rows it moved. */
    fun applyResults(results: List<LedgerResult>): List<Transfer> {
        if (results.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            UPDATE transfers t
            SET status = v.status, reject_reason = v.reason, resulted_at = now()
            FROM unnest(CAST(:ids AS uuid[]), CAST(:statuses AS text[]), CAST(:reasons AS text[])) AS v(transfer_id, status, reason)
            WHERE t.transfer_id = v.transfer_id AND t.status IN (:accepted, :published)
            RETURNING t.*
            """,
        )
            .param("ids", results.map { it.transferId.toString() }.toTypedArray())
            .param("statuses", results.map { it.outcome.name }.toTypedArray())
            .param("reasons", results.map { it.rejectReason }.toTypedArray())
            .param("accepted", TransferStatus.ACCEPTED.name)
            .param("published", TransferStatus.PUBLISHED.name)
            .query { rs, _ -> rs.toTransfer() }
            .list()
    }

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
        resultedAt = getObject("resulted_at", Timestamp::class.java)?.toInstant(),
    )
}
