package com.ledgerlab.gateway.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ClosedPeriod(
    Instant periodStart,
    Instant closedAt,
    @Schema(description = "Transfers accepted in the period, any status.", requiredMode = Schema.RequiredMode.REQUIRED)
    long entryCount
) {
}
