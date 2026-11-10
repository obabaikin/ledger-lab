package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record Resend(
    ResendKind kind,
    int number,
    long offsetMs,
    SendResult result,
    @Schema(nullable = true) @Nullable Integer httpStatus,
    long latencyMs
) {
}
