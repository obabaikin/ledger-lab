package com.ledgerlab.control.generator;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The generator's PeriodReconciliation; {@link #report} is there once the state is DONE. */
public record ClientReconciliation(
    Instant periodStart,
    ClientReconciliationState state,
    @Nullable String failure,
    @Nullable ClientReconciliationReport report
) {

    public ClientReconciliation {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(state, "state");
    }
}
