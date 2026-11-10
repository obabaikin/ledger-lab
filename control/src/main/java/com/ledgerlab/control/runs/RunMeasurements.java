package com.ledgerlab.control.runs;

import com.ledgerlab.control.reporting.RunStats;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record RunMeasurements(
    @Nullable Double clientLatencyP50Ms,
    @Nullable Double clientLatencyP99Ms,
    @Nullable RunStats stats,
    @Nullable Long ledgerDuplicatesAtLeast,
    List<ChaosInRun> chaos
) {
    public static RunMeasurements none() {
        return new RunMeasurements(null, null, null, null, List.of());
    }
}
