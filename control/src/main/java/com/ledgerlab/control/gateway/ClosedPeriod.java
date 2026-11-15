package com.ledgerlab.control.gateway;

import java.time.Instant;
import java.util.Objects;

public record ClosedPeriod(Instant periodStart, Instant closedAt, long entryCount) {

    public ClosedPeriod {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(closedAt, "closedAt");
    }
}
