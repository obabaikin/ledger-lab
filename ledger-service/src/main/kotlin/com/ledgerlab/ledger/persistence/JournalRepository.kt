package com.ledgerlab.ledger.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JournalRepository(private val jdbc: JdbcClient) {

    fun insert(runId: UUID, transferId: UUID, accountNo: Int, amountCents: Long) {
        jdbc.sql(
            """
            INSERT INTO journal_entries (run_id, transfer_id, account_no, amount_cents)
            VALUES (:runId, :transferId, :accountNo, :amount)
            """,
        )
            .param("runId", runId)
            .param("transferId", transferId)
            .param("accountNo", accountNo)
            .param("amount", amountCents)
            .update()
    }
}
