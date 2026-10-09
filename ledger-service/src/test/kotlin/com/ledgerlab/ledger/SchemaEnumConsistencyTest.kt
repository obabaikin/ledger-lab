package com.ledgerlab.ledger

import com.ledgerlab.ledger.domain.AccountStatus
import com.ledgerlab.common.TransferOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Kotlin enums mirror CHECK constraints in the schema; this fails if one side gains a value the other lacks. */
@Transactional
class SchemaEnumConsistencyTest : IntegrationTest() {

    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `every TransferOutcome is accepted by the transfers table`() {
        val runId = UUID.randomUUID()
        TransferOutcome.entries.forEach { outcome ->
            val inserted = jdbc.sql(
                """
                INSERT INTO transfers (run_id, transfer_id, from_account, to_account, amount_cents, outcome, reject_reason)
                VALUES (:runId, :transferId, 1, 2, 1, :outcome, :reason)
                """,
            )
                .param("runId", runId)
                .param("transferId", UUID.randomUUID())
                .param("outcome", outcome.name)
                .param("reason", if (outcome == TransferOutcome.REJECTED) "SAME_ACCOUNT" else null)
                .update()
            assertThat(inserted).isEqualTo(1)
        }
    }

    @Test
    fun `every AccountStatus is accepted by the accounts table`() {
        val runId = UUID.randomUUID()
        AccountStatus.entries.forEachIndexed { i, status ->
            val inserted = jdbc.sql(
                """
                INSERT INTO accounts (run_id, account_no, opening_balance_cents, balance_cents, status, daily_limit_cents)
                VALUES (:runId, :no, 0, 0, :status, 0)
                """,
            )
                .param("runId", runId)
                .param("no", i + 1)
                .param("status", status.name)
                .update()
            assertThat(inserted).isEqualTo(1)
        }
    }
}
