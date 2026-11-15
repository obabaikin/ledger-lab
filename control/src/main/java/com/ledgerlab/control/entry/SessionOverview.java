package com.ledgerlab.control.entry;

import java.util.List;
import java.util.UUID;

public record SessionOverview(
    UUID sessionId,
    boolean inviteValid,
    boolean chaosAvailable,
    long chaosReadyInSeconds,
    List<Integer> loadTpsOptions,
    int defaultLoadTps,
    List<Integer> loadDurationOptions,
    int defaultLoadSeconds,
    long loadReadyInSeconds
) {
    public SessionOverview {
        loadTpsOptions = List.copyOf(loadTpsOptions);
        loadDurationOptions = List.copyOf(loadDurationOptions);
    }
}
