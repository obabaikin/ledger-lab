package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "An address to mail an invite link to.")
public record InviteRequest(
    @Schema(description = "Where the link goes; trimmed and lowercased.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable String email,
    @Schema(description = "The Turnstile widget's answer; null where the check is off.", nullable = true)
    @Nullable String turnstileToken
) {
}
