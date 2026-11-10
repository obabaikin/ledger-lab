package com.ledgerlab.control.gateway;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClosedPeriod(UUID runId, Instant closedAt) {
    public ClosedPeriod {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(closedAt, "closedAt");
    }
}
