package com.ledgerlab.control.periods;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record BankPeriod(
    Instant start,
    Instant end,
    PeriodState state,
    @Nullable Instant closingStartedAt,
    @Nullable Instant closedAt,
    @Nullable Long entryCount,
    @Nullable Instant drainProgressAt,
    @Nullable Instant booksFinalAt,
    @Nullable Instant checkRequestedAt,
    @Nullable Instant clientRequestedAt,
    @Nullable Verdict verdict,
    @Nullable PeriodFailure failure,
    @Nullable Instant finishedAt,
    @Nullable PeriodNumbers numbers
) {
}
