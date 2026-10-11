package com.ledgerlab.common

enum class RejectReason {
    INVALID_AMOUNT,
    SAME_ACCOUNT,
    UNKNOWN_ACCOUNT,
    BLOCKED_ACCOUNT,
    INSUFFICIENT_FUNDS,
    DAILY_LIMIT_EXCEEDED,
}
