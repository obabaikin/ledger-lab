package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "The ledger instances that were crashed.")
public record ChaosView(
    @Schema(description = "When the instances were crashed, by control's clock.")
    Instant at,
    @Schema(description = "Instances that took the crash; those that did not answer are left out.")
    List<String> instances
) {
}
