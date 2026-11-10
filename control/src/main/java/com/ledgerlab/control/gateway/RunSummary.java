package com.ledgerlab.control.gateway;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record RunSummary(
    UUID runId,
    long accepted,
    long published,
    long posted,
    long rejected,
    Map<String, Long> rejectionsByReason,
    @Nullable Double latencyP50Ms,
    @Nullable Double latencyP99Ms
) {
    public RunSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(rejectionsByReason, "rejectionsByReason");
    }
}
