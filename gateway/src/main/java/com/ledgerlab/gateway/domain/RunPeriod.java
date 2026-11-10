package com.ledgerlab.gateway.domain;

import java.time.Instant;
import java.util.UUID;

public record RunPeriod(UUID runId, Instant closedAt, long entryCount) {
}
