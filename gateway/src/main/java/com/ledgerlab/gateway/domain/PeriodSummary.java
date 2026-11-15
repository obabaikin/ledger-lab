package com.ledgerlab.gateway.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

public record PeriodSummary(
    Instant periodStart,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long accepted,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long published,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long posted,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long rejected,
    // Keyed by the reason as stored, so one this gateway does not know yet is counted too.
    Map<String, Long> rejectionsByReason
) {
}
