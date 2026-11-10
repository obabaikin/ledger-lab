package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "The invite from the link.")
public record CreateSessionRequest(
    @Schema(description = "Token of the invite link.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable String token
) {
}
