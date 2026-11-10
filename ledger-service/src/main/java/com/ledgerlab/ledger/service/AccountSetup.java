package com.ledgerlab.ledger.service;

public record AccountSetup(
    int accountCount,
    long openingBalanceCents,
    int blockedAccounts,
    long poolOpeningBalanceCents
) {
    /** A transfer needs a sender and a different receiver. */
    public static final int MIN_ACCOUNTS = 2;

    public AccountSetup {
        if (accountCount < MIN_ACCOUNTS) {
            throw new IllegalArgumentException("accountCount must be at least " + MIN_ACCOUNTS);
        }
        if (blockedAccounts < 0 || blockedAccounts >= accountCount) {
            throw new IllegalArgumentException("blockedAccounts must leave at least one active account");
        }
        if (openingBalanceCents < 0 || poolOpeningBalanceCents < 0) {
            throw new IllegalArgumentException("opening balances must not be negative");
        }
    }
}
