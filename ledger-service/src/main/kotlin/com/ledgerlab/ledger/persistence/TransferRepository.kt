package com.ledgerlab.ledger.persistence

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.domain.RejectReason
import com.ledgerlab.ledger.domain.TransferOutcome
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TransferRepository(private val jdbc: JdbcClient) {

    /**
     * Primary key (run_id, transfer_id) is the idempotency guard.
     * Returns false when the transfer was already recorded, including by a concurrent transaction.
     */
    fun insertIfAbsent(command: TransferCommand, rejectReason: RejectReason?): Boolean =
        jdbc.sql(
            """
            INSERT INTO transfers (run_id, transfer_id, from_account, to_account, amount_cents, outcome, reject_reason)
            VALUES (:runId, :transferId, :from, :to, :amount, :outcome, :reason)
            ON CONFLICT (run_id, transfer_id) DO NOTHING
            """,
        )
            .param("runId", command.runId)
            .param("transferId", command.transferId)
            .param("from", command.fromAccount)
            .param("to", command.toAccount)
            .param("amount", command.amountCents)
            .param("outcome", (if (rejectReason == null) TransferOutcome.POSTED else TransferOutcome.REJECTED).name)
            .param("reason", rejectReason?.name)
            .update() == 1

    fun deleteRun(runId: UUID): Int =
        jdbc.sql("DELETE FROM transfers WHERE run_id = :runId").param("runId", runId).update()
}
