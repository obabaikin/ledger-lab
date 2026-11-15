package com.ledgerlab.ledger.pool;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.pool")
public record PoolProperties(
    long openingBalanceCents,
    int hotAccounts,
    Duration hotSweepInterval,
    Duration round,
    Duration tickInterval,
    int maxBatch
) {
}
