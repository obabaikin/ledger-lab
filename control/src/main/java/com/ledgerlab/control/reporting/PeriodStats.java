package com.ledgerlab.control.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Schema(description = "A period's numbers from the bank's events, as the reporting service counted them.")
public record PeriodStats(
    Instant periodStart,
    @Schema(description = "When the reporting service read them.")
    Instant at,
    long accepted,
    long commandsSeen,
    long posted,
    long rejected,
    long postedAmountCents,
    Map<String, Long> rejectionsByReason
) {

    public PeriodStats {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(at, "at");
        rejectionsByReason = Map.copyOf(Objects.requireNonNull(rejectionsByReason, "rejectionsByReason"));
    }
}
