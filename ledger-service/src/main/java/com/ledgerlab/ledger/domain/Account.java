package com.ledgerlab.ledger.domain;

import java.util.UUID;

public record Account(
    UUID runId,
    int accountNo,
    long openingBalanceCents,
    long balanceCents,
    AccountStatus status,
    long dailyLimitCents,
    long dailySpentCents
) {
    public Account withBalanceAndDailySpent(long balanceCents, long dailySpentCents) {
        return new Account(runId, accountNo, openingBalanceCents, balanceCents, status, dailyLimitCents, dailySpentCents);
    }
}
