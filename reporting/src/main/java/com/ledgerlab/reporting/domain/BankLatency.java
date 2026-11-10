package com.ledgerlab.reporting.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "processedAt − acceptedAt over transfers with both known; null when there are none.")
public record BankLatency(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable Double p50Ms,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable Double p99Ms
) {
}
