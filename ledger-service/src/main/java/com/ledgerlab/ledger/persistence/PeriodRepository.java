package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.ledger.domain.AccountMismatch;
import com.ledgerlab.ledger.domain.Period;
import com.ledgerlab.ledger.domain.PeriodCheck;
import com.ledgerlab.ledger.domain.TransferKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Every query names the period's bounds on accepted_at, so it reads the period's partition and no other. */
@Repository
public class PeriodRepository {

    private static final String CHECK_LOCK_PREFIX = "ledger.period-check.";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PeriodRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** For the caller's transaction alone. */
    public void limitStatements(Duration timeout) {
        jdbc.sql("SET LOCAL statement_timeout = '" + timeout.toMillis() + "ms'").update();
    }

    public TransferCounts countTransfers(Period period) {
        var posted = 0L;
        var rejected = 0L;
        var poolPostings = 0L;
        var byReason = new EnumMap<RejectReason, Long>(RejectReason.class);
        var rows = jdbc.sql(
            """
            SELECT kind, outcome, reject_reason, count(*) AS n
            FROM transfers
            WHERE accepted_at >= :start AND accepted_at < :end
            GROUP BY kind, outcome, reject_reason
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .query((rs, rowNum) -> new CountRow(
                TransferKind.valueOf(rs.getString("kind")),
                TransferOutcome.valueOf(rs.getString("outcome")),
                rs.getString("reject_reason"),
                rs.getLong("n")))
            .list();
        for (var row : rows) {
            if (row.kind() == TransferKind.POOL) {
                poolPostings += row.count();
            } else if (row.outcome() == TransferOutcome.POSTED) {
                posted += row.count();
            } else {
                rejected += row.count();
                if (row.rejectReason() != null) {
                    byReason.merge(RejectReason.valueOf(row.rejectReason()), row.count(), Long::sum);
                }
            }
        }
        return new TransferCounts(posted, rejected, byReason, poolPostings);
    }

    private record CountRow(TransferKind kind, TransferOutcome outcome, @Nullable String rejectReason, long count) {
    }

    public Optional<PeriodCheck> findCheck(Instant periodStart) {
        return jdbc.sql("SELECT report FROM period_checks WHERE period_start = :start")
            .param("start", Sql.timestamptz(periodStart))
            .query(String.class)
            .optional()
            .map(report -> objectMapper.readValue(report, PeriodCheck.class));
    }

    public void saveCheck(Instant periodStart, PeriodCheck check) {
        jdbc.sql("INSERT INTO period_checks (period_start, report) VALUES (:start, CAST(:report AS jsonb))")
            .param("start", Sql.timestamptz(periodStart))
            .param("report", objectMapper.writeValueAsString(check))
            .update();
    }

    /** Held until the transaction ends: of all instances, one checks a period. */
    public boolean tryLockCheck(Instant periodStart) {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", CHECK_LOCK_PREFIX + periodStart)
            .query(Boolean.class)
            .single();
    }

    public boolean hasSnapshot(Instant periodStart) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account_snapshots WHERE period_start = :start)")
            .param("start", Sql.timestamptz(periodStart))
            .query(Boolean.class)
            .single();
    }

    /**
     * Every account's balance as it stood when the period ended, taken later: today's balance and the credits not yet
     * applied, less everything the journal holds from after the period (ADR 0018, ADR 0021). All of it in one statement
     * and inside the database: the accounts never travel to the service.
     */
    public void snapshotBalancesAtEndOf(Period period) {
        jdbc.sql(
            """
            INSERT INTO account_snapshots (period_start, account_no, balance_cents)
            SELECT :start, a.account_no, a.balance_cents + coalesce(d.pending, 0) - coalesce(j.later, 0)
            FROM accounts a
            LEFT JOIN (SELECT account_no, sum(amount_cents) AS pending FROM deferred_credits GROUP BY account_no) d USING (account_no)
            LEFT JOIN (
                SELECT account_no, sum(amount_cents) AS later FROM journal_entries WHERE accepted_at >= :end GROUP BY account_no
            ) j USING (account_no)
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .update();
    }

    public record AccountMismatches(long total, List<AccountMismatch> examples) {
    }

    /** Accounts whose balance moved over the period by another amount than their journal entries of the period. */
    public AccountMismatches accountsOutOfBalance(Period period, int exampleLimit) {
        var examples = new ArrayList<AccountMismatch>();
        var total = new long[1];
        jdbc.sql(
            """
            WITH moved AS (
                SELECT account_no, sum(amount_cents) AS cents
                FROM journal_entries
                WHERE accepted_at >= :start AND accepted_at < :end
                GROUP BY account_no
            ), mismatch AS (
                SELECT cur.account_no,
                       coalesce(m.cents, 0) AS journal_cents,
                       cur.balance_cents - prev.balance_cents AS balance_change_cents
                FROM account_snapshots cur
                JOIN account_snapshots prev ON prev.account_no = cur.account_no AND prev.period_start = :previousStart
                LEFT JOIN moved m ON m.account_no = cur.account_no
                WHERE cur.period_start = :start
                  AND cur.balance_cents - prev.balance_cents <> coalesce(m.cents, 0)
            )
            SELECT account_no, journal_cents, balance_change_cents, count(*) OVER () AS total
            FROM mismatch
            ORDER BY account_no
            LIMIT :limit
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .param("previousStart", Sql.timestamptz(period.previous().start()))
            .param("limit", exampleLimit)
            .query(rs -> {
                total[0] = rs.getLong("total");
                examples.add(new AccountMismatch(rs.getInt("account_no"), rs.getLong("journal_cents"), rs.getLong("balance_change_cents")));
            });
        return new AccountMismatches(total[0], examples);
    }

    public record JournalTotals(long entries, long debitsCents, long creditsCents) {
    }

    public JournalTotals journalTotals(Period period) {
        return jdbc.sql(
            """
            SELECT count(*) AS entries,
                   coalesce(-sum(amount_cents) FILTER (WHERE amount_cents < 0), 0) AS debits,
                   coalesce(sum(amount_cents) FILTER (WHERE amount_cents > 0), 0) AS credits
            FROM journal_entries
            WHERE accepted_at >= :start AND accepted_at < :end
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .query((rs, rowNum) -> new JournalTotals(rs.getLong("entries"), rs.getLong("debits"), rs.getLong("credits")))
            .single();
    }

    public record InvalidJournal(long total, List<UUID> examples) {
    }

    /**
     * Posted transfers without exactly the debit and the credit their log entry implies, rejected ones with any entry,
     * and entries of no transfer at all.
     */
    public InvalidJournal transfersWithInvalidJournal(Period period, int exampleLimit) {
        var examples = new ArrayList<UUID>();
        var total = new long[1];
        jdbc.sql(
            """
            WITH booked AS (
                SELECT transfer_id,
                       count(*) AS entries,
                       count(*) FILTER (WHERE amount_cents < 0) AS debits,
                       min(amount_cents) AS debit_cents,
                       max(amount_cents) AS credit_cents,
                       min(account_no) FILTER (WHERE amount_cents < 0) AS debited,
                       min(account_no) FILTER (WHERE amount_cents > 0) AS credited
                FROM journal_entries
                WHERE accepted_at >= :start AND accepted_at < :end
                GROUP BY transfer_id
            ), logged AS (
                SELECT transfer_id, from_account, to_account, amount_cents, outcome
                FROM transfers
                WHERE accepted_at >= :start AND accepted_at < :end
            ), invalid AS (
                SELECT coalesce(t.transfer_id, b.transfer_id) AS transfer_id
                FROM logged t
                FULL JOIN booked b ON b.transfer_id = t.transfer_id
                WHERE t.transfer_id IS NULL
                   OR (t.outcome = :rejected AND b.transfer_id IS NOT NULL)
                   OR (t.outcome = :posted AND (
                           b.transfer_id IS NULL OR b.entries <> 2 OR b.debits <> 1
                           OR b.debit_cents <> -t.amount_cents OR b.credit_cents <> t.amount_cents
                           OR b.debited <> t.from_account OR b.credited <> t.to_account))
            )
            SELECT transfer_id, count(*) OVER () AS total
            FROM invalid
            ORDER BY transfer_id
            LIMIT :limit
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .param("posted", TransferOutcome.POSTED.name())
            .param("rejected", TransferOutcome.REJECTED.name())
            .param("limit", exampleLimit)
            .query(rs -> {
                total[0] = rs.getLong("total");
                examples.add(rs.getObject("transfer_id", UUID.class));
            });
        return new InvalidJournal(total[0], examples);
    }

    public void deleteChecksBefore(Instant periodStart) {
        jdbc.sql("DELETE FROM account_snapshots WHERE period_start < :start").param("start", Sql.timestamptz(periodStart)).update();
        jdbc.sql("DELETE FROM period_checks WHERE period_start < :start").param("start", Sql.timestamptz(periodStart)).update();
    }
}
