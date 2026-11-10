package com.ledgerlab.control.ledger;

public record OpenAccountsRequest(
    int accountCount,
    long openingBalanceCents,
    int blockedAccounts,
    long dailyLimitCents
) {
}
