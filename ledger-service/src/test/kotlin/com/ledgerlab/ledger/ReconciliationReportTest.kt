package com.ledgerlab.ledger

import com.ledgerlab.ledger.domain.ReconciliationCheck
import com.ledgerlab.ledger.domain.ReconciliationReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReconciliationReportTest {

    private fun report(
        openingTotal: Long = 1_000,
        balanceTotal: Long = 1_000,
        posted: Long = 3,
        journalEntries: Long = 6,
        journalSum: Long = 0,
        invalidJournal: Long = 0,
        outOfBalance: Long = 0,
    ) = ReconciliationReport(
        runId = UUID.randomUUID(),
        accounts = 2,
        openingTotalCents = openingTotal,
        balanceTotalCents = balanceTotal,
        postedTransfers = posted,
        rejectedTransfers = 0,
        rejectionsByReason = emptyMap(),
        journalEntries = journalEntries,
        journalSumCents = journalSum,
        debitsCents = 0,
        creditsCents = 0,
        transfersWithInvalidJournal = invalidJournal,
        accountsOutOfBalance = outOfBalance,
    )

    @Test
    fun `is balanced when every check passes`() {
        val report = report()

        assertThat(report.balanced).isTrue()
        assertThat(report.failedChecks).isEmpty()
        assertThat(report.checks.map { it.check }).containsExactly(*ReconciliationCheck.entries.toTypedArray())
    }

    @Test
    fun `names the check that failed`() {
        assertThat(report(balanceTotal = 999).failedChecks).containsExactly(ReconciliationCheck.TOTAL_BALANCE_CONSERVED)
        assertThat(report(journalSum = 5).failedChecks).containsExactly(ReconciliationCheck.JOURNAL_SUMS_TO_ZERO)
        assertThat(report(invalidJournal = 1).failedChecks).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG)
        assertThat(report(outOfBalance = 1).failedChecks).containsExactly(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL)
    }
}
