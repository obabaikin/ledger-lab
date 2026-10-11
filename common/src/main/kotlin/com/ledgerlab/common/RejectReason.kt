package com.ledgerlab.common

/** Why the ledger rejected a transfer; carried by [TransferResultEvent]. */
enum class RejectReason {
    INVALID_AMOUNT,
    SAME_ACCOUNT,
    UNKNOWN_ACCOUNT,
    BLOCKED_ACCOUNT,
    INSUFFICIENT_FUNDS,
    DAILY_LIMIT_EXCEEDED,
}
