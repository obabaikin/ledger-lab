package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Deletion is set.")
public record AddressDeletionAccepted(
    @Schema(description = "From when the address is deleted; asking again keeps the first time.")
    Instant deleteAt,
    @Schema(description = "Whether an earlier request had already set the deletion.")
    boolean alreadyAsked
) {
}
