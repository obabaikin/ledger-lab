package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A run to start.")
public record StartRunRequest(
    @Schema(description = "Idempotency key within the session: a repeat with the same body gets the same run.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable UUID requestId,
    @Schema(description = "Transfers per second, one of the session's tpsOptions.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable Integer tps,
    @Schema(description = "Seconds, one of the session's durationOptions.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable Integer durationSeconds
) {
}
