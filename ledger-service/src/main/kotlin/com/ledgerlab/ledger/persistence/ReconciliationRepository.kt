package com.ledgerlab.ledger.persistence

import com.ledgerlab.ledger.domain.ReconciliationReport
import com.ledgerlab.common.RejectReason
import com.ledgerlab.common.TransferOutcome
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReconciliationRepository(private val jdbc: JdbcClient) {

    fun reconcile(runId: UUID): ReconciliationReport {
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

        val invalidJournal = jdbc.sql(
            """
            SELECT count(*) FROM (
                SELECT t.transfer_id
                FROM transfers t
                LEFT JOIN journal_entries j ON j.run_id = t.run_id AND j.transfer_id = t.transfer_id
                WHERE t.run_id = :runId
                GROUP BY t.run_id, t.transfer_id
                HAVING (t.outcome = :posted AND (
                           count(j.id) <> 2
                        OR count(j.id) FILTER (WHERE j.account_no = t.from_account AND j.amount_cents = -t.amount_cents) <> 1
                        OR count(j.id) FILTER (WHERE j.account_no = t.to_account AND j.amount_cents = t.amount_cents) <> 1))
                    OR (t.outcome = :rejected AND count(j.id) <> 0)
            ) bad
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
