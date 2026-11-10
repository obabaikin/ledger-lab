package com.ledgerlab.ledger.persistence;

import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.AccountStatus;
import com.ledgerlab.ledger.domain.Books;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Opens the accounts that do not exist yet; an existing one keeps its balance and status. */
    public int openMissing(int firstAccountNo, int lastAccountNo, int firstBlockedAccountNo, long openingBalanceCents) {
        return jdbc.sql(
            """
            INSERT INTO accounts (account_no, opening_balance_cents, balance_cents, status)
            SELECT no, :opening, :opening, CASE WHEN no >= :firstBlocked THEN :blocked ELSE :active END
            FROM generate_series(:first, :last) AS no
            ON CONFLICT (account_no) DO NOTHING
            """)
            .param("opening", openingBalanceCents)
            .param("first", firstAccountNo)
            .param("last", lastAccountNo)
            .param("firstBlocked", firstBlockedAccountNo)
            .param("blocked", AccountStatus.BLOCKED.name())
            .param("active", AccountStatus.ACTIVE.name())
            .update();
    }

    public List<Account> findAll() {
        return jdbc.sql("SELECT * FROM accounts ORDER BY account_no").query(Account.class).list();
    }

    /**
     * FOR NO KEY UPDATE, the lock an UPDATE of the balance takes anyway: journal and deferred-credit rows take KEY SHARE
     * on the account through their foreign keys, and FOR UPDATE would wait for every open batch that credits it.
     * One order for every caller, so a batch, the credit applier and the pool wait for each other instead of deadlocking.
     */
    public List<Account> lockAll(Set<Integer> accountNos) {
        if (accountNos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
            """
            SELECT * FROM accounts
            WHERE account_no = ANY (CAST(:accountNos AS integer[]))
            ORDER BY account_no
            FOR NO KEY UPDATE
            """)
            .param("accountNos", numbers(accountNos))
            .query(Account.class)
            .list();
    }

    public List<Account> findAll(Set<Integer> accountNos) {
        if (accountNos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM accounts WHERE account_no = ANY (CAST(:accountNos AS integer[]))")
            .param("accountNos", numbers(accountNos))
            .query(Account.class)
            .list();
    }

    public void addToBalances(Map<Integer, Long> amounts) {
        if (amounts.isEmpty()) {
            return;
        }
        // An UPDATE takes its rows in no set order; lockAll takes them in the order every caller uses.
        lockAll(amounts.keySet());
        var accountNos = List.copyOf(amounts.keySet());
        jdbc.sql(
            """
            UPDATE accounts a SET balance_cents = a.balance_cents + v.amount
            FROM unnest(CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[])) AS v(account_no, amount)
            WHERE a.account_no = v.account_no
            """)
            .param("accountNos", numbers(accountNos))
            .param("amounts", accountNos.stream().map(no -> Objects.requireNonNull(amounts.get(no), "amount of a key of the same map")).toArray(Long[]::new))
            .update();
    }

    public void updateBalances(Collection<Account> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        jdbc.sql(
            """
            UPDATE accounts a SET balance_cents = v.balance
            FROM unnest(CAST(:accountNos AS integer[]), CAST(:balances AS bigint[])) AS v(account_no, balance)
            WHERE a.account_no = v.account_no
            """)
            .param("accountNos", accounts.stream().map(Account::accountNo).toArray(Integer[]::new))
            .param("balances", accounts.stream().map(Account::balanceCents).toArray(Long[]::new))
            .update();
    }

    /** A credit is in the journal before it is in the balance, so the pending ones count as balance (ADR 0018). */
    public Books books() {
        return jdbc.sql(
            """
            SELECT count(*) AS accounts,
                   coalesce(sum(opening_balance_cents), 0) AS opening_total,
                   coalesce(sum(balance_cents), 0) + (SELECT coalesce(sum(amount_cents), 0) FROM deferred_credits) AS balance_total
            FROM accounts
            """)
            .query((rs, rowNum) -> new Books(rs.getLong("accounts"), rs.getLong("opening_total"), rs.getLong("balance_total")))
            .single();
    }

    private static Integer[] numbers(Collection<Integer> accountNos) {
        return accountNos.toArray(Integer[]::new);
    }
}
