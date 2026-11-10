package com.ledgerlab.reporting.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A run's transfers as seen in the bank's events; one consistent snapshot, lagging the books while events are on the way.")
public record RunStats(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    UUID runId,
    @Schema(description = "Time of the snapshot, by the reporting database's clock.", requiredMode = Schema.RequiredMode.REQUIRED)
    Instant at,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    StatsTotals totals,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    BankLatency bankLatency,
    @Schema(description = "Increments per second, oldest first.", requiredMode = Schema.RequiredMode.REQUIRED)
    List<SecondStats> series
) {
}
