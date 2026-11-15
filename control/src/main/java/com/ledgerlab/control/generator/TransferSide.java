package com.ledgerlab.control.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "A transfer as one side holds it.")
public record TransferSide(
    int fromAccount,
    int toAccount,
    long amountCents,
    @Schema(nullable = true)
    @Nullable String status,
    @Schema(nullable = true)
    @Nullable String rejectReason
) {
}
