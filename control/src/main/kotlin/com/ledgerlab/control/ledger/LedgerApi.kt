package com.ledgerlab.control.ledger

import java.util.UUID

/** Control's model of the ledger's internal API, checked against ledger-service/openapi.json. */
object LedgerApi {
    const val ACCOUNTS_PATH = "/internal/runs/{runId}/accounts"
    const val RECONCILIATION_PATH = "/internal/runs/{runId}/reconciliation"
    const val RUN_PATH = "/internal/runs/{runId}"
    /** Not in the spec: actuator; 503 until the transfer consumer holds a partition. */
    const val READINESS_PATH = "/actuator/health/readiness"
}

data class OpenAccountsRequest(
    val accountCount: Int,
    val openingBalanceCents: Long,
    val blockedAccounts: Int,
    val dailyLimitCents: Long,
)

enum class ReconciliationCheck {
    TOTAL_BALANCE_CONSERVED,
    JOURNAL_SUMS_TO_ZERO,
    JOURNAL_MATCHES_TRANSFER_LOG,
    EVERY_ACCOUNT_MATCHES_JOURNAL,
}

data class ReconciliationReport(
    val runId: UUID,
    val accounts: Long,
    val balanced: Boolean,
    val failedChecks: List<ReconciliationCheck>,
    val postedTransfers: Long,
    val rejectedTransfers: Long,
    val rejectionsByReason: Map<String, Long>,
)
