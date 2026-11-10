package com.ledgerlab.control.api;

import com.ledgerlab.control.metrics.LiveView;
import com.ledgerlab.control.runs.Run;
import com.ledgerlab.control.runs.RunResult;
import com.ledgerlab.control.runs.RunState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A run as control sees it now.")
public record RunView(
    UUID runId,
    RunState state,
    int tps,
    int durationSeconds,
    Instant createdAt,
    @Schema(description = "When the run left the queue; null while queued or if it expired.", nullable = true)
    @Nullable Instant admittedAt,
    @Schema(description = "When the run reached a final state; null before.", nullable = true)
    @Nullable Instant finishedAt,
    @Schema(description = "Place in the queue, from 1; only while queued.", nullable = true)
    @Nullable Long position,
    @Schema(description = "WARM_UP while queued because the bank is not ready: the ledger takes its partitions (after a start or a crash), the gateway warms up, or Prometheus cannot tell; null otherwise.", nullable = true)
    @Nullable WaitingFor waitingFor,
    @Schema(description = "When the clients started sending; null before.", nullable = true)
    @Nullable Instant startedAt,
    @Schema(description = "A stop was asked for while the run was running.")
    boolean stopRequested,
    @Schema(description = "When the bank closed the run's period, by the bank's clock; null before.", nullable = true)
    @Nullable Instant closedAt,
    @Schema(description = "When control first saw the books final: every accepted transfer applied; reconciling from then on. null before.", nullable = true)
    @Nullable Instant booksFinalAt,
    @Schema(description = "The verdict; only once reconciled. Only BALANCED is green.", nullable = true)
    @Nullable RunResult result,
    @Schema(description = "Why the run failed without a verdict; only when failed.", nullable = true)
    @Nullable String failure,
    @Schema(description = "Live numbers; only in a run's own GET while running or draining, else null. The report keeps the final ones.", nullable = true)
    @Nullable LiveView live
) {
    static RunView of(Run run, @Nullable LiveView live, @Nullable WaitingFor waitingFor) {
        return new RunView(
            run.runId(), run.state(), run.tps(), run.durationSeconds(), run.createdAt(), run.admittedAt(), run.finishedAt(), run.position(),
            waitingFor, run.startedAt(), run.stopRequested(), run.closedAt(), run.booksFinalAt(), run.result(), run.failure(), live);
    }
}
