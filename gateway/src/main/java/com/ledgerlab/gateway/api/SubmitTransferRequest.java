package com.ledgerlab.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "A transfer to submit.")
public record SubmitTransferRequest(
    @Schema(description = "Idempotency key: a repeat with the same body gets the same transfer.", nullable = true)
    @NotNull UUID transferId,
    @Schema(nullable = true)
    @NotNull UUID runId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) int fromAccount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) int toAccount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) long amountCents
) {
}
