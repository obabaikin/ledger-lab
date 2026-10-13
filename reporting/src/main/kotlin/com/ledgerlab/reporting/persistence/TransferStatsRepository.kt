package com.ledgerlab.reporting.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class AcceptedTransfer(val transferId: UUID, val runId: UUID, val amountCents: Long, val acceptedAt: Instant)

data class TransferOutcomeRow(
    val transferId: UUID,
    val runId: UUID,
    val outcome: String,
    val rejectReason: String?,
    val processedAt: Instant,
)

@Repository
class TransferStatsRepository(private val jdbc: JdbcTemplate) {

    fun upsertAccepted(rows: List<AcceptedTransfer>) {
        jdbc.batchUpdate(
            """
            INSERT INTO transfer_stats (transfer_id, run_id, amount_cents, accepted_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (transfer_id) DO UPDATE SET amount_cents = EXCLUDED.amount_cents, accepted_at = EXCLUDED.accepted_at
            """.trimIndent(),
            lockOrdered(rows) { it.transferId },
            BATCH_SIZE,
        ) { ps, row ->
            ps.setObject(1, row.transferId)
            ps.setObject(2, row.runId)
            ps.setLong(3, row.amountCents)
            ps.setTimestamp(4, Timestamp.from(row.acceptedAt))
        }
    }

    fun upsertOutcomes(rows: List<TransferOutcomeRow>) {
        jdbc.batchUpdate(
            """
            INSERT INTO transfer_stats (transfer_id, run_id, outcome, reject_reason, processed_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (transfer_id) DO UPDATE
                SET outcome = EXCLUDED.outcome, reject_reason = EXCLUDED.reject_reason, processed_at = EXCLUDED.processed_at
            """.trimIndent(),
            lockOrdered(rows) { it.transferId },
            BATCH_SIZE,
        ) { ps, row ->
            ps.setObject(1, row.transferId)
            ps.setObject(2, row.runId)
            ps.setString(3, row.outcome)
            ps.setString(4, row.rejectReason)
            ps.setTimestamp(5, Timestamp.from(row.processedAt))
        }
    }

    /**
     * Commands and results of the same transfers can be written by different instances at once; taking row locks
     * in one order keeps them from deadlocking. The rewritten multi-row insert also rejects a key twice in one
     * statement, and a redelivered message can bring the same transfer twice in one batch.
     */
    private fun <T> lockOrdered(rows: List<T>, key: (T) -> UUID): List<T> =
        rows.associateBy(key).toSortedMap().values.toList()

    fun deleteRun(runId: UUID): Int = jdbc.update("DELETE FROM transfer_stats WHERE run_id = ?", runId)

    private companion object {
        const val BATCH_SIZE = 500
    }
}
