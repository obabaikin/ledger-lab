package com.ledgerlab.ledger.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.periods")
public record PeriodsProperties(
    Duration length,
    Duration retention,
    Duration partitionsAhead,
    Duration maintenanceInterval,
    Duration lockTimeout,
    Duration snapshotRetention,
    int unpartitionedDeleteBatch
) {
    /** Partitions are named to the minute of their period's start. */
    public PeriodsProperties {
        if (length.compareTo(Duration.ofMinutes(1)) < 0 || !length.equals(Duration.ofMinutes(length.toMinutes()))) {
            throw new IllegalArgumentException("ledger.periods.length must be whole minutes, at least one");
        }
    }
}
