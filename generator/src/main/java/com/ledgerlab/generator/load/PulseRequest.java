package com.ledgerlab.generator.load;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record PulseRequest(
    @Schema(description = "New transfers a second while the pulse lasts; at most the generator's ceiling.", requiredMode = Schema.RequiredMode.REQUIRED)
    @Nullable Integer tps,
    @Schema(description = "How long the pulse lasts, from now.", requiredMode = Schema.RequiredMode.REQUIRED)
    @Nullable Integer seconds
) {
}
