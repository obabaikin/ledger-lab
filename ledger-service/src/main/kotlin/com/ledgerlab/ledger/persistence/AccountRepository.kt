package com.ledgerlab.ledger.persistence

import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.AccountKey
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

    /**
     * FOR NO KEY UPDATE, the lock an UPDATE of the balance takes anyway: journal and deferred-credit rows take KEY SHARE
     * on the account through their foreign keys, and FOR UPDATE would wait for every open batch that credits it.
     * One order for every caller, so a batch and the credit applier wait for each other instead of deadlocking.
     */
    fun lockAll(keys: Set<AccountKey>): List<Account> {
        if (keys.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            SELECT * FROM accounts
            WHERE (run_id, account_no) IN (SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:accountNos AS integer[])))
            ORDER BY run_id, account_no
            FOR NO KEY UPDATE
            """,
        )
            .keyParams(keys)
            .query(Account::class.java)
            .list()
            .requireNoNulls()
    }

    fun findAll(keys: Set<AccountKey>): List<Account> {
        if (keys.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            SELECT * FROM accounts
            WHERE (run_id, account_no) IN (SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:accountNos AS integer[])))
            """,
        )
            .keyParams(keys)
            .query(Account::class.java)
            .list()
            .requireNoNulls()
    }

    fun addToBalances(amounts: Map<AccountKey, Long>) {
        if (amounts.isEmpty()) return
        // An UPDATE takes its rows in no set order; lockAll takes them in the order every caller uses.
        lockAll(amounts.keys)
        val keys = amounts.keys.toList()
        jdbc.sql(
            """
            UPDATE accounts a SET balance_cents = a.balance_cents + v.amount
            FROM unnest(CAST(:runIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[]))
                AS v(run_id, account_no, amount)
            WHERE a.run_id = v.run_id AND a.account_no = v.account_no
            """,
        )
            .keyParams(keys)
            .param("amounts", keys.map { amounts.getValue(it) }.toTypedArray())
            .update()
    }

    fun updateBalances(accounts: List<Account>) {
        if (accounts.isEmpty()) return
        jdbc.sql(
            """
            UPDATE accounts a
            SET balance_cents = v.balance, daily_spent_cents = v.spent
            FROM unnest(
                CAST(:runIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:balances AS bigint[]), CAST(:spent AS bigint[])
            ) AS v(run_id, account_no, balance, spent)
            WHERE a.run_id = v.run_id AND a.account_no = v.account_no
            """,
        )
            .keyParams(accounts.map { AccountKey(it.runId, it.accountNo) })
            .param("balances", accounts.map { it.balanceCents }.toTypedArray())
            .param("spent", accounts.map { it.dailySpentCents }.toTypedArray())
            .update()
    }

    fun deleteRun(runId: UUID): Int =
        jdbc.sql("DELETE FROM accounts WHERE run_id = :runId").param("runId", runId).update()

    private fun JdbcClient.StatementSpec.keyParams(keys: Collection<AccountKey>) =
        param("runIds", keys.map { it.runId.toString() }.toTypedArray())
            .param("accountNos", keys.map { it.accountNo }.toTypedArray())
}
