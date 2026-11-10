package com.ledgerlab.control.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Schema(description = "A run's numbers from the bank's events, as the reporting service had them at `at`; they trail the books while events are on the way.")
public record RunStats(
    UUID runId,
    @Schema(description = "Time of the snapshot, by the reporting database's clock.")
    Instant at,
    StatsTotals totals,
    BankLatency bankLatency,
    @Schema(description = "Increments per second, oldest first.")
    List<SecondStats> series
) {
    public RunStats {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(bankLatency, "bankLatency");
        series = List.copyOf(Objects.requireNonNull(series, "series"));
    }
}
