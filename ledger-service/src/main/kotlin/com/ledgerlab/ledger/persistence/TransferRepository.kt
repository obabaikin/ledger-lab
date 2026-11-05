package com.ledgerlab.ledger.persistence

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.common.RejectReason
import com.ledgerlab.common.TransferOutcome
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

data class TransferKey(val runId: UUID, val transferId: UUID)

data class TransferRow(val command: TransferCommand, val rejectReason: RejectReason?)

@Repository
class TransferRepository(private val jdbc: JdbcClient) {

    fun findRecorded(keys: Set<TransferKey>): Set<TransferKey> {
        if (keys.isEmpty()) return emptySet()
        return jdbc.sql(
            """
            SELECT run_id, transfer_id FROM transfers
            WHERE (run_id, transfer_id) IN (SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:transferIds AS uuid[])))
            """,
        )
            .param("runIds", keys.map { it.runId.toString() }.toTypedArray())
            .param("transferIds", keys.map { it.transferId.toString() }.toTypedArray())
            .query { rs, _ -> rs.toKey() }
            .set()
    }

    /**
     * Primary key (run_id, transfer_id) is the idempotency guard.
     * Returns the keys this call inserted; a transfer already recorded, also by a concurrent transaction, is skipped.
     */
    fun insertAllIfAbsent(rows: List<TransferRow>): Set<TransferKey> {
        if (rows.isEmpty()) return emptySet()
        return jdbc.sql(
            """
            INSERT INTO transfers (run_id, transfer_id, from_account, to_account, amount_cents, outcome, reject_reason)
            SELECT * FROM unnest(
                CAST(:runIds AS uuid[]), CAST(:transferIds AS uuid[]), CAST(:froms AS integer[]), CAST(:tos AS integer[]),
                CAST(:amounts AS bigint[]), CAST(:outcomes AS text[]), CAST(:reasons AS text[])
            )
            ON CONFLICT (run_id, transfer_id) DO NOTHING
            RETURNING run_id, transfer_id
            """,
        )
            .param("runIds", rows.map { it.command.runId.toString() }.toTypedArray())
            .param("transferIds", rows.map { it.command.transferId.toString() }.toTypedArray())
            .param("froms", rows.map { it.command.fromAccount }.toTypedArray())
            .param("tos", rows.map { it.command.toAccount }.toTypedArray())
            .param("amounts", rows.map { it.command.amountCents }.toTypedArray())
            .param("outcomes", rows.map { (if (it.rejectReason == null) TransferOutcome.POSTED else TransferOutcome.REJECTED).name }.toTypedArray())
            .param("reasons", rows.map { it.rejectReason?.name }.toTypedArray())
            .query { rs, _ -> rs.toKey() }
            .set()
    }

    fun deleteRun(runId: UUID): Int =
        jdbc.sql("DELETE FROM transfers WHERE run_id = :runId").param("runId", runId).update()

    private fun ResultSet.toKey() =
        TransferKey(getObject("run_id", UUID::class.java), getObject("transfer_id", UUID::class.java))
}
