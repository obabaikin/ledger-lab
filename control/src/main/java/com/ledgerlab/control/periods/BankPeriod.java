package com.ledgerlab.control.periods;

import com.ledgerlab.control.gateway.PeriodSummary;
import com.ledgerlab.control.ledger.LedgerReconciliation;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** {@link #gatewayCounts} and {@link #ledgerCounts} are the bank's counts read once they agreed: the verdict rests on them. */
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
    @Nullable PeriodNumbers numbers,
    @Nullable Instant nextActionAt,
    @Nullable PeriodSummary gatewayCounts,
    @Nullable LedgerReconciliation ledgerCounts
) {
}
