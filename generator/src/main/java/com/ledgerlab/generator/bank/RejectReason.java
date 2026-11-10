package com.ledgerlab.generator.bank;

public enum RejectReason {
    INVALID_AMOUNT,
    SAME_ACCOUNT,
    UNKNOWN_ACCOUNT,
    BLOCKED_ACCOUNT,
    INSUFFICIENT_FUNDS,
    DAILY_LIMIT_EXCEEDED,
}
