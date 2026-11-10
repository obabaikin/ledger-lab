package com.ledgerlab.control.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "processedAt − acceptedAt (ledger clock minus gateway clock); null without both.")
public record BankLatency(@Schema(nullable = true) @Nullable Double p50Ms, @Schema(nullable = true) @Nullable Double p99Ms) {
}
