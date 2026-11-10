package com.ledgerlab.control.runs;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record Run(
    UUID runId,
    UUID sessionId,
    UUID requestId,
    int tps,
    int durationSeconds,
    RunState state,
    Instant createdAt,
    @Nullable Instant admittedAt,
    @Nullable Instant finishedAt,
    @Nullable Long position,
    @Nullable Instant setupStartedAt,
    boolean startSent,
    @Nullable Instant startedAt,
    boolean stopRequested,
    boolean clientLost,
    @Nullable Instant drainStartedAt,
    @Nullable Instant closedAt,
    @Nullable Instant drainProgressAt,
    @Nullable Instant booksFinalAt,
    @Nullable RunResult result,
    @Nullable String failure
) {
    public static final Duration QUOTA_WINDOW = Duration.ofHours(1);
}
