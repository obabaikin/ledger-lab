package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SimulationStatus(
    UUID runId,
    SimulationState state,
    int targetTps,
    int durationSeconds,
    Instant startedAt,
    @Schema(nullable = true) @Nullable Instant finishedAt,
    long requested,
    long acknowledged,
    long posted,
    long rejected,
    Map<RejectReason, Long> rejectedByReason,
    long failed,
    // Sent, but no answer settled it: the gateway may have the transfer.
    long unknown,
    long retries,
    @Schema(description = JournalTotals.REFUSED_DESCRIPTION)
    long refused,
    // Second sends of the same transfer, as a user pressing twice; not new transfers.
    long duplicatesSent,
    long inFlight,
    double actualTps,
    @Schema(description = "p50 of the time a waiting client's first press took to a final status, ms; presses whose wait ran out are left out; null before one.", nullable = true)
    @Nullable Double waitedLatencyP50Ms,
    @Schema(description = "p99, as waitedLatencyP50Ms.", nullable = true)
    @Nullable Double waitedLatencyP99Ms,
    @Schema(description = "New transfers per second of the run, from its start; index 0 is the first second.")
    List<Long> requestedPerSecond
) {
}
