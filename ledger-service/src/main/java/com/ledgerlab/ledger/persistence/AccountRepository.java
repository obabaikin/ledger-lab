package com.ledgerlab.ledger.persistence;

import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.AccountKey;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchJdbc;

    public AccountRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchJdbc) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
    }

    public void insertAll(List<Account> accounts) {
        var rows = accounts.stream()
            .map(it -> new MapSqlParameterSource()
                .addValue("runId", it.runId())
                .addValue("accountNo", it.accountNo())
                .addValue("opening", it.openingBalanceCents())
                .addValue("balance", it.balanceCents())
                .addValue("status", it.status().name())
                .addValue("dailyLimit", it.dailyLimitCents())
                .addValue("dailySpent", it.dailySpentCents()))
            .toArray(MapSqlParameterSource[]::new);
        batchJdbc.batchUpdate(
            """
            INSERT INTO accounts (run_id, account_no, opening_balance_cents, balance_cents, status, daily_limit_cents, daily_spent_cents)
            VALUES (:runId, :accountNo, :opening, :balance, :status, :dailyLimit, :dailySpent)
            """,
            rows);
    }

    public List<Account> findAll(UUID runId) {
        return jdbc.sql("SELECT * FROM accounts WHERE run_id = :runId ORDER BY account_no")
            .param("runId", runId)
            .query(Account.class)
            .list();
    }

    /**
     * FOR NO KEY UPDATE, the lock an UPDATE of the balance takes anyway: journal and deferred-credit rows take KEY SHARE
     * on the account through their foreign keys, and FOR UPDATE would wait for every open batch that credits it.
     * One order for every caller, so a batch and the credit applier wait for each other instead of deadlocking.
     */
    public List<Account> lockAll(Set<AccountKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return keyParams(
            jdbc.sql(
                """
                SELECT * FROM accounts
                WHERE (run_id, account_no) IN (SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:accountNos AS integer[])))
                ORDER BY run_id, account_no
                FOR NO KEY UPDATE
                """),
            keys)
            .query(Account.class)
            .list();
    }

    public List<Account> findAll(Set<AccountKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return keyParams(
            jdbc.sql(
                """
                SELECT * FROM accounts
                WHERE (run_id, account_no) IN (SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:accountNos AS integer[])))
                """),
            keys)
            .query(Account.class)
            .list();
    }

    public void addToBalances(Map<AccountKey, Long> amounts) {
        if (amounts.isEmpty()) {
            return;
        }
        // An UPDATE takes its rows in no set order; lockAll takes them in the order every caller uses.
        lockAll(amounts.keySet());
        var keys = List.copyOf(amounts.keySet());
        keyParams(
            jdbc.sql(
                """
                UPDATE accounts a SET balance_cents = a.balance_cents + v.amount
                FROM unnest(CAST(:runIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[]))
                    AS v(run_id, account_no, amount)
                WHERE a.run_id = v.run_id AND a.account_no = v.account_no
                """),
            keys)
            .param("amounts", keys.stream().map(key -> Objects.requireNonNull(amounts.get(key), "amount of a key of the same map")).toArray(Long[]::new))
            .update();
    }

    public void updateBalances(List<Account> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        keyParams(
            jdbc.sql(
                """
                UPDATE accounts a
                SET balance_cents = v.balance, daily_spent_cents = v.spent
                FROM unnest(
                    CAST(:runIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:balances AS bigint[]), CAST(:spent AS bigint[])
                ) AS v(run_id, account_no, balance, spent)
                WHERE a.run_id = v.run_id AND a.account_no = v.account_no
                """),
            accounts.stream().map(it -> new AccountKey(it.runId(), it.accountNo())).toList())
            .param("balances", accounts.stream().map(Account::balanceCents).toArray(Long[]::new))
            .param("spent", accounts.stream().map(Account::dailySpentCents).toArray(Long[]::new))
            .update();
    }

    public int deleteRun(UUID runId) {
        return jdbc.sql("DELETE FROM accounts WHERE run_id = :runId").param("runId", runId).update();
    }

    private static JdbcClient.StatementSpec keyParams(JdbcClient.StatementSpec statement, Collection<AccountKey> keys) {
        return statement
            .param("runIds", keys.stream().map(it -> it.runId().toString()).toArray(String[]::new))
            .param("accountNos", keys.stream().map(AccountKey::accountNo).toArray(Integer[]::new));
    }
}
