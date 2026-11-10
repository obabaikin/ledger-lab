package com.ledgerlab.control.generator;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SimulationStatus(
    UUID runId,
    SimulationState state,
    long requested,
    long acknowledged,
    long failed,
    long unknown,
    long retries,
    long refused,
    long duplicatesSent,
    @Schema(nullable = true) @Nullable Double waitedLatencyP50Ms,
    @Schema(nullable = true) @Nullable Double waitedLatencyP99Ms,
    List<Long> requestedPerSecond
) {
    public SimulationStatus {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(state, "state");
        requestedPerSecond = List.copyOf(Objects.requireNonNull(requestedPerSecond, "requestedPerSecond"));
    }

    @JsonCreator
    static SimulationStatus read(
        UUID runId,
        SimulationState state,
        long requested,
        long acknowledged,
        long failed,
        long unknown,
        long retries,
        @Nullable Long refused,
        long duplicatesSent,
        @Nullable Double waitedLatencyP50Ms,
        @Nullable Double waitedLatencyP99Ms,
        @Nullable List<Long> requestedPerSecond
    ) {
        return new SimulationStatus(
            runId, state, requested, acknowledged, failed, unknown, retries,
            refused != null ? refused : 0, duplicatesSent, waitedLatencyP50Ms, waitedLatencyP99Ms,
            requestedPerSecond != null ? requestedPerSecond : List.of());
    }
}
