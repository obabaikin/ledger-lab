package com.ledgerlab.ledger.service;

public record AccountSetup(
    int accountCount,
    long openingBalanceCents,
    int blockedAccounts,
    long dailyLimitCents
) {
    /** A transfer needs a sender and a different receiver. */
    public static final long MIN_ACCOUNTS = 2L;
    public static final long MAX_ACCOUNTS = 10_000L;

    public AccountSetup {
        if (accountCount < MIN_ACCOUNTS) {
            throw new IllegalArgumentException("accountCount must be at least " + MIN_ACCOUNTS);
        }
        if (blockedAccounts < 0 || blockedAccounts >= accountCount) {
            throw new IllegalArgumentException("blockedAccounts must leave at least one active account");
        }
        if (openingBalanceCents < 0) {
            throw new IllegalArgumentException("openingBalanceCents must not be negative");
        }
        if (dailyLimitCents < 0) {
            throw new IllegalArgumentException("dailyLimitCents must not be negative");
        }
    }
}
