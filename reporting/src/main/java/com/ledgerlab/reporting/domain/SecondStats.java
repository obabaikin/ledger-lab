package com.ledgerlab.reporting.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record SecondStats(
    @Schema(description = "Start of the second.", requiredMode = Schema.RequiredMode.REQUIRED)
    Instant second,
    @Schema(description = "By acceptedAt, or by processedAt while the command is not seen yet.", requiredMode = Schema.RequiredMode.REQUIRED)
    long accepted,
    @Schema(description = "By processedAt.", requiredMode = Schema.RequiredMode.REQUIRED)
    long posted,
    @Schema(description = "By processedAt.", requiredMode = Schema.RequiredMode.REQUIRED)
    long rejected,
    @Schema(description = "By processedAt.", requiredMode = Schema.RequiredMode.REQUIRED)
    long postedAmountCents,
    @Schema(description = "Bank latency of transfers processed in this second; null when none has a known acceptedAt.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable Double bankP50Ms,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable Double bankP99Ms
) {
}
