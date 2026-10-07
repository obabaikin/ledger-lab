package com.ledgerlab.ledger.persistence

import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.AccountStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AccountRepository(
    private val jdbc: JdbcClient,
    private val batchJdbc: NamedParameterJdbcTemplate,
) {

    fun insertAll(accounts: List<Account>) {
        val rows = accounts.map {
            MapSqlParameterSource()
                .addValue("runId", it.runId)
                .addValue("accountNo", it.accountNo)
                .addValue("opening", it.openingBalanceCents)
                .addValue("balance", it.balanceCents)
                .addValue("status", it.status.name)
                .addValue("dailyLimit", it.dailyLimitCents)
                .addValue("dailySpent", it.dailySpentCents)
        }
        batchJdbc.batchUpdate(
            """
            INSERT INTO accounts (run_id, account_no, opening_balance_cents, balance_cents, status, daily_limit_cents, daily_spent_cents)
            VALUES (:runId, :accountNo, :opening, :balance, :status, :dailyLimit, :dailySpent)
            """,
            rows.toTypedArray(),
        )
    }

    fun findAll(runId: UUID): List<Account> =
        jdbc.sql("SELECT * FROM accounts WHERE run_id = :runId ORDER BY account_no")
            .param("runId", runId)
            .query(Account::class.java)
            .list()
            .requireNoNulls()

    fun lock(runId: UUID, accountNo: Int): Account? =
        jdbc.sql("SELECT * FROM accounts WHERE run_id = :runId AND account_no = :accountNo FOR UPDATE")
            .param("runId", runId)
            .param("accountNo", accountNo)
            .query(Account::class.java)
            .optional()
            .orElse(null)

    fun debit(runId: UUID, accountNo: Int, amountCents: Long) {
        jdbc.sql(
            """
            UPDATE accounts
            SET balance_cents = balance_cents - :amount, daily_spent_cents = daily_spent_cents + :amount
            WHERE run_id = :runId AND account_no = :accountNo
            """,
        )
            .param("amount", amountCents)
            .param("runId", runId)
            .param("accountNo", accountNo)
            .update()
    }

    fun credit(runId: UUID, accountNo: Int, amountCents: Long) {
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + :amount WHERE run_id = :runId AND account_no = :accountNo")
            .param("amount", amountCents)
            .param("runId", runId)
            .param("accountNo", accountNo)
            .update()
    }

    fun deleteRun(runId: UUID): Int =
        jdbc.sql("DELETE FROM accounts WHERE run_id = :runId").param("runId", runId).update()
}
