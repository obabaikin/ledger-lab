package com.ledgerlab.ledger.persistence

import com.ledgerlab.ledger.domain.ReconciliationReport
import com.ledgerlab.common.RejectReason
import com.ledgerlab.common.TransferOutcome
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

@ConfigurationProperties("ledger.reconciliation")
data class ReconciliationProperties(val statementTimeout: Duration)

@Repository
class ReconciliationRepository(private val jdbc: JdbcClient, private val props: ReconciliationProperties) {

    /** Runs inside the caller's transaction: the statement timeout is set for it alone. */
    fun reconcile(runId: UUID): ReconciliationReport {
        jdbc.sql("SET LOCAL statement_timeout = '${props.statementTimeout.toMillis()}ms'").update()

        val accountTotals = jdbc.sql(
            """
            SELECT count(*) AS accounts,
                   coalesce(sum(opening_balance_cents), 0) AS opening_total,
                   coalesce(sum(balance_cents), 0) AS balance_total
            FROM accounts WHERE run_id = :runId
            """,
        ).param("runId", runId).query().singleRow()

        val transferTotals = jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE outcome = :posted) AS posted,
                   count(*) FILTER (WHERE outcome = :rejected) AS rejected
            FROM transfers WHERE run_id = :runId
            """,
        )
            .param("runId", runId)
            .param("posted", TransferOutcome.POSTED.name)
            .param("rejected", TransferOutcome.REJECTED.name)
            .query().singleRow()

        val rejections = jdbc.sql(
            "SELECT reject_reason, count(*) AS n FROM transfers WHERE run_id = :runId AND outcome = :rejected GROUP BY reject_reason",
        )
            .param("runId", runId)
            .param("rejected", TransferOutcome.REJECTED.name)
            .query().listOfRows()
            .associate { RejectReason.valueOf(it["reject_reason"] as String) to (it["n"] as Number).toLong() }

        val journalTotals = jdbc.sql(
            "SELECT count(*) AS entries, coalesce(sum(amount_cents), 0) AS total FROM journal_entries WHERE run_id = :runId",
        ).param("runId", runId).query().singleRow()

        // Per-transfer lookup on purpose: its plan does not depend on statistics of a run Postgres has not analyzed yet,
        // which a JOIN with GROUP BY does (ADR 0016).
        val invalidJournal = jdbc.sql(
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
            """,
        )
            .param("runId", runId)
            .param("posted", TransferOutcome.POSTED.name)
            .param("rejected", TransferOutcome.REJECTED.name)
            .query(Long::class.java).single()

        val outOfBalance = jdbc.sql(
            """
            SELECT count(*)
            FROM accounts a
            LEFT JOIN (
                SELECT account_no, sum(amount_cents) AS movement
                FROM journal_entries WHERE run_id = :runId GROUP BY account_no
            ) j ON j.account_no = a.account_no
            WHERE a.run_id = :runId
              AND a.opening_balance_cents + coalesce(j.movement, 0) <> a.balance_cents
            """,
        ).param("runId", runId).query(Long::class.java).single()

        return ReconciliationReport(
            runId = runId,
            accounts = accountTotals.long("accounts"),
            openingTotalCents = accountTotals.long("opening_total"),
            balanceTotalCents = accountTotals.long("balance_total"),
            postedTransfers = transferTotals.long("posted"),
            rejectedTransfers = transferTotals.long("rejected"),
            rejectionsByReason = rejections,
            journalEntries = journalTotals.long("entries"),
            journalSumCents = journalTotals.long("total"),
            transfersWithInvalidJournal = invalidJournal,
            accountsOutOfBalance = outOfBalance,
        )
    }

    private fun Map<String, Any?>.long(column: String): Long = (getValue(column) as Number).toLong()
}
