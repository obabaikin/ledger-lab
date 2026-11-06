package com.ledgerlab.ledger.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class JournalEntry(val runId: UUID, val transferId: UUID, val accountNo: Int, val amountCents: Long)

@Repository
class JournalRepository(private val jdbc: JdbcClient) {

    fun insertAll(entries: List<JournalEntry>) {
        if (entries.isEmpty()) return
        jdbc.sql(
            """
            INSERT INTO journal_entries (run_id, transfer_id, account_no, amount_cents)
            SELECT * FROM unnest(
                CAST(:runIds AS uuid[]), CAST(:transferIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[])
            )
            """,
        )
            .param("runIds", entries.map { it.runId.toString() }.toTypedArray())
            .param("transferIds", entries.map { it.transferId.toString() }.toTypedArray())
            .param("accountNos", entries.map { it.accountNo }.toTypedArray())
            .param("amounts", entries.map { it.amountCents }.toTypedArray())
            .update()
    }
}
