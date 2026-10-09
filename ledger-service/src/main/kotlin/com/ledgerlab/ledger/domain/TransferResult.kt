package com.ledgerlab.ledger.domain

enum class RejectReason {
    INVALID_AMOUNT,
    SAME_ACCOUNT,
    UNKNOWN_ACCOUNT,
    BLOCKED_ACCOUNT,
    INSUFFICIENT_FUNDS,
    DAILY_LIMIT_EXCEEDED,
}

sealed interface TransferResult {
    data object Posted : TransferResult
    data class Rejected(val reason: RejectReason) : TransferResult
    data object AlreadyProcessed : TransferResult
}
