package com.ledgerlab.control.metrics;

import com.ledgerlab.control.reporting.RunStats;
import com.ledgerlab.control.runs.ChaosInRun;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "Live numbers of a running or draining run; each part is the last one read and keeps it while its source cannot be read.")
public record LiveView(
    @Schema(description = "The bank's numbers from its events; null until the reporting service answered once.", nullable = true)
    @Nullable RunStats stats,
    @Schema(description = "null until the generator answered once.", nullable = true)
    @Nullable ClientCounts clients,
    @Schema(description = "null until Prometheus answered once.", nullable = true)
    @Nullable List<PodView> pods,
    @Schema(description = "Redeliveries the ledger skipped, at least this many: from monitoring, and a killed instance takes its count along. Not part of the verdict.", nullable = true)
    @Nullable Long ledgerDuplicatesAtLeast,
    @Schema(description = "When control last read any part, by its own clock.")
    Instant readAt,
    @Schema(description = "Ledger crashes since the run was admitted, in order; empty if none.")
    List<ChaosInRun> chaos
) {
    public LiveView withChaos(List<ChaosInRun> chaos) {
        return new LiveView(stats, clients, pods, ledgerDuplicatesAtLeast, readAt, chaos);
    }
}
