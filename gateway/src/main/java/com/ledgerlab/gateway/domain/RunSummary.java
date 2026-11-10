package com.ledgerlab.gateway.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record RunSummary(
    UUID runId,
    long accepted,
    long published,
    long posted,
    long rejected,
    // Keyed by the reason as stored, so one this gateway does not know yet is counted too.
    Map<String, Long> rejectionsByReason,
    @Schema(nullable = true)
    @Nullable Double latencyP50Ms,
    @Schema(nullable = true)
    @Nullable Double latencyP99Ms
) {
}
