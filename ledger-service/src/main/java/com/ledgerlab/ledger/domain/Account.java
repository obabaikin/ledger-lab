package com.ledgerlab.ledger.domain;

public record Account(
    int accountNo,
    long openingBalanceCents,
    long balanceCents,
    AccountStatus status
) {
    /** The bank's own account behind cash pooling (ADR 0023); clients' numbers start at {@link #FIRST_CLIENT_ACCOUNT_NO}. */
    public static final int POOL_ACCOUNT_NO = 0;
    public static final int FIRST_CLIENT_ACCOUNT_NO = 1;

    public Account withBalance(long balanceCents) {
        return new Account(accountNo, openingBalanceCents, balanceCents, status);
    }
}
