package com.ledgerlab.ledger.persistence

import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.common.TransferResultEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class ResultOutboxRepository(private val jdbc: JdbcClient) {

    /** Claims a batch for this transaction; other publishers skip the claimed rows. */
    fun claimUnpublished(limit: Int): List<TransferResultEvent> =
        jdbc.sql(
            """
            SELECT run_id, transfer_id, outcome, reject_reason, processed_at
            FROM transfers
            WHERE result_published_at IS NULL
            ORDER BY processed_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
        )
            .param("limit", limit)
            .query { rs, _ ->
                TransferResultEvent(
                    runId = rs.getObject("run_id", UUID::class.java),
                    transferId = rs.getObject("transfer_id", UUID::class.java),
                    outcome = TransferOutcome.valueOf(rs.getString("outcome")),
                    rejectReason = rs.getString("reject_reason"),
                    processedAt = rs.getObject("processed_at", Timestamp::class.java).toInstant(),
                )
            }
            .list()
            .requireNoNulls()

    fun markPublished(events: List<TransferResultEvent>) {
        if (events.isEmpty()) return
        jdbc.sql(
            """
            UPDATE transfers SET result_published_at = now()
            WHERE (run_id, transfer_id) IN (
                SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:transferIds AS uuid[]))
            )
            """,
        )
            .param("runIds", events.map { it.runId.toString() }.toTypedArray())
            .param("transferIds", events.map { it.transferId.toString() }.toTypedArray())
            .update()
    }
}
