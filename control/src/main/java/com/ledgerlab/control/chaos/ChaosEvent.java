package com.ledgerlab.control.chaos;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Schema(description = "A crash of the ledger's instances, set off from the page.")
public record ChaosEvent(
    Instant at,
    @Schema(description = "Instances that took the crash.")
    List<String> instances
) {

    public ChaosEvent {
        Objects.requireNonNull(at, "at");
        instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
    }
}
