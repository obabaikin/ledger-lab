package com.ledgerlab.gateway.api;

import java.time.Instant;
import java.util.UUID;

public record ClosedPeriod(UUID runId, Instant closedAt) {
}
