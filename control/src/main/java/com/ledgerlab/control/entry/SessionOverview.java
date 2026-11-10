package com.ledgerlab.control.entry;

import java.util.List;
import java.util.UUID;

public record SessionOverview(
    UUID sessionId,
    boolean inviteValid,
    int runsPerHour,
    long runsLastHour,
    List<Integer> tpsOptions,
    int defaultTps,
    List<Integer> durationOptions,
    boolean chaosAvailable,
    long chaosReadyInSeconds
) {
}
