package com.ledgerlab.ledger.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.periods")
public record PeriodsProperties(
    Duration length,
    Duration retention,
    Duration partitionsAhead,
    Duration maintenanceInterval,
    Duration dropLockTimeout,
    Duration snapshotRetention
) {
}
