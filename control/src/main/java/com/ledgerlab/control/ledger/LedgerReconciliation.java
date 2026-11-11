package com.ledgerlab.control.ledger;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The ledger's PeriodReconciliation; {@link #check} is null until the ledger has made it. */
public record LedgerReconciliation(
    Instant periodStart,
    long postedTransfers,
    long rejectedTransfers,
    Map<String, Long> rejectionsByReason,
    long poolPostings,
    @Nullable PeriodCheck check
) {

    public LedgerReconciliation {
        Objects.requireNonNull(periodStart, "periodStart");
        rejectionsByReason = Map.copyOf(Objects.requireNonNull(rejectionsByReason, "rejectionsByReason"));
    }
}
