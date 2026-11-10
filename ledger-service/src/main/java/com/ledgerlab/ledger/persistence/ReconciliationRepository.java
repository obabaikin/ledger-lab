package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.ledger.domain.ReconciliationReport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbc;
    private final ReconciliationProperties props;

    public ReconciliationRepository(JdbcClient jdbc, ReconciliationProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Runs inside the caller's transaction: the statement timeout is set for it alone. */
    public ReconciliationReport reconcile(UUID runId) {
        jdbc.sql("SET LOCAL statement_timeout = '" + props.statementTimeout().toMillis() + "ms'").update();

        var accountTotals = jdbc.sql(
            """
            SELECT count(*) AS accounts,
                   coalesce(sum(opening_balance_cents), 0) AS opening_total,
                   coalesce(sum(balance_cents), 0)
                       + (SELECT coalesce(sum(amount_cents), 0) FROM deferred_credits WHERE run_id = :runId) AS balance_total
            FROM accounts WHERE run_id = :runId
            """)
            .param("runId", runId).query().singleRow();

        var transferTotals = jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE outcome = :posted) AS posted,
                   count(*) FILTER (WHERE outcome = :rejected) AS rejected
            FROM transfers WHERE run_id = :runId
            """)
            .param("runId", runId)
            .param("posted", TransferOutcome.POSTED.name())
            .param("rejected", TransferOutcome.REJECTED.name())
            .query().singleRow();

        var rejections = new LinkedHashMap<RejectReason, Long>();
        jdbc.sql("SELECT reject_reason, count(*) AS n FROM transfers WHERE run_id = :runId AND outcome = :rejected GROUP BY reject_reason")
            .param("runId", runId)
            .param("rejected", TransferOutcome.REJECTED.name())
            .query().listOfRows()
            .forEach(row -> rejections.put(RejectReason.valueOf((String) Objects.requireNonNull(row.get("reject_reason"), "reject_reason")), toLong(row, "n")));

        var journalTotals = jdbc.sql(
            """
            SELECT count(*) AS entries,
                   coalesce(sum(amount_cents), 0) AS total,
                   coalesce(-sum(amount_cents) FILTER (WHERE amount_cents < 0), 0) AS debits,
                   coalesce(sum(amount_cents) FILTER (WHERE amount_cents > 0), 0) AS credits
            FROM journal_entries WHERE run_id = :runId
            """)
            .param("runId", runId).query().singleRow();

        // Per-transfer lookup on purpose: its plan does not depend on statistics of a run Postgres has not analyzed yet,
        // which a join with an aggregate does (ADR 0016).
        var invalidJournal = jdbc.sql(
            """
            SELECT count(*)
            FROM transfers t
            CROSS JOIN LATERAL (
                SELECT count(*) AS entries,
                       count(*) FILTER (WHERE j.account_no = t.from_account AND j.amount_cents = -t.amount_cents) AS debits,
                       count(*) FILTER (WHERE j.account_no = t.to_account AND j.amount_cents = t.amount_cents) AS credits
                FROM journal_entries j
                WHERE j.run_id = t.run_id AND j.transfer_id = t.transfer_id
            ) j
            WHERE t.run_id = :runId
              AND ((t.outcome = :posted AND (j.entries <> 2 OR j.debits <> 1 OR j.credits <> 1))
                OR (t.outcome = :rejected AND j.entries <> 0))
            """)
            .param("runId", runId)
            .param("posted", TransferOutcome.POSTED.name())
            .param("rejected", TransferOutcome.REJECTED.name())
            .query(Long.class).single();

        // Per-account lookup for the same reason as the journal check above (ADR 0016). A deferred credit is in the
        // journal before it is in the balance, so the pending ones count as balance (ADR 0018).
        var outOfBalance = jdbc.sql(
            """
            SELECT count(*)
            FROM accounts a
            CROSS JOIN LATERAL (
                SELECT coalesce(sum(j.amount_cents), 0) AS movement
                FROM journal_entries j
                WHERE j.run_id = a.run_id AND j.account_no = a.account_no
            ) j
            CROSS JOIN LATERAL (
                SELECT coalesce(sum(d.amount_cents), 0) AS pending
                FROM deferred_credits d
                WHERE d.run_id = a.run_id AND d.account_no = a.account_no
            ) d
            WHERE a.run_id = :runId
              AND a.opening_balance_cents + j.movement <> a.balance_cents + d.pending
            """)
            .param("runId", runId).query(Long.class).single();

        return new ReconciliationReport(
            runId,
            toLong(accountTotals, "accounts"),
            toLong(accountTotals, "opening_total"),
            toLong(accountTotals, "balance_total"),
            toLong(transferTotals, "posted"),
            toLong(transferTotals, "rejected"),
            rejections,
            toLong(journalTotals, "entries"),
            toLong(journalTotals, "total"),
            toLong(journalTotals, "debits"),
            toLong(journalTotals, "credits"),
            invalidJournal,
            outOfBalance);
    }

    private static long toLong(Map<String, @Nullable Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }
}
