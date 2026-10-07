package com.ledgerlab.ledger.domain

import java.util.UUID

enum class ReconciliationCheck(val description: String) {
    TOTAL_BALANCE_CONSERVED("total balance equals total opening balance"),
    JOURNAL_SUMS_TO_ZERO("journal sums to zero"),
    JOURNAL_MATCHES_TRANSFER_LOG("every posted transfer has exactly the debit and credit its log entry implies; rejected transfers have none"),
    EVERY_ACCOUNT_MATCHES_JOURNAL("every account balance equals opening balance plus its journal"),
}

data class CheckResult(val check: ReconciliationCheck, val passed: Boolean) {
    val description: String get() = check.description
}

/**
 * Independent cross-check of a run's ledger state. All three views must agree:
 * account balances, the double-entry journal, and the transfer log.
 */
data class ReconciliationReport(
    val runId: UUID,
    val accounts: Long,
    val openingTotalCents: Long,
    val balanceTotalCents: Long,
    val postedTransfers: Long,
    val rejectedTransfers: Long,
    val rejectionsByReason: Map<RejectReason, Long>,
    val journalEntries: Long,
    val journalSumCents: Long,
    val transfersWithInvalidJournal: Long,
    val accountsOutOfBalance: Long,
) {
    val checks: List<CheckResult> = listOf(
        CheckResult(ReconciliationCheck.TOTAL_BALANCE_CONSERVED, balanceTotalCents == openingTotalCents),
        CheckResult(ReconciliationCheck.JOURNAL_SUMS_TO_ZERO, journalSumCents == 0L),
        CheckResult(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG, transfersWithInvalidJournal == 0L),
        CheckResult(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL, accountsOutOfBalance == 0L),
    )

    val failedChecks: List<ReconciliationCheck> = checks.filter { !it.passed }.map { it.check }

    val balanced: Boolean = failedChecks.isEmpty()
}
