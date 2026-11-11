package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Schema(description = "Where the client reconciliation of a period stands. It is kept in memory: after a restart of the generator it is NOT_STARTED again.")
public record PeriodReconciliation(
    Instant periodStart,
    ReconciliationState state,
    @Schema(description = "Set when FAILED.", nullable = true)
    @Nullable ReconciliationFailure failure,
    @Schema(description = "Set when DONE.", nullable = true)
    @Nullable ClientReconciliationReport report
) {
    static PeriodReconciliation of(Instant periodStart, ReconciliationState state) {
        return new PeriodReconciliation(periodStart, state, null, null);
    }
}
