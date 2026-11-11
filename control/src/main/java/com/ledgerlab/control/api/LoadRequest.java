package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "A load pulse: one of the session's speed and length options.")
public record LoadRequest(
    @Schema(description = "The bank's speed while the pulse lasts, in transfers a second.", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonDeserialize(using = WholeNumber.class) @Nullable Integer tps,
    @Schema(description = "How long the pulse lasts, in seconds.", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonDeserialize(using = WholeNumber.class) @Nullable Integer seconds
) {
}
