package com.ledgerlab.control.runs;

import com.ledgerlab.control.chaos.ChaosEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Schema(description = "A crash of the ledger while the run was active.")
public record ChaosInRun(
    @Schema(description = "Milliseconds from the clients' start; 0 if the crash came before it.")
    long offsetMs,
    @Schema(description = "Instances that took the crash.")
    List<String> instances
) {
    public ChaosInRun {
        instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
    }

    public static ChaosInRun of(ChaosEvent event, Instant startedAt) {
        return new ChaosInRun(Math.max(Duration.between(startedAt, event.at()).toMillis(), 0), event.instances());
    }
}
