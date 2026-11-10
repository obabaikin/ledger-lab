package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "A mailed link whose address is to be deleted.")
public record AddressDeletionRequest(
    @Schema(description = "The link's ref, from any mail sent to the address.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    @Nullable String token
) {
}
