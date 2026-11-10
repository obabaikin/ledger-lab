package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Body of an error answer.")
public record ApiError(
    @Schema(description = "Names the case; a kebab-case code tells it apart, a phrase together with the status code.") String error
) {
}
