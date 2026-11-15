package com.ledgerlab.control.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record PeriodSummary(
    Instant periodStart,
    long accepted,
    long published,
    long posted,
    long rejected,
    Map<String, Long> rejectionsByReason
) {

    public PeriodSummary {
        Objects.requireNonNull(periodStart, "periodStart");
        rejectionsByReason = Map.copyOf(Objects.requireNonNull(rejectionsByReason, "rejectionsByReason"));
    }
}
