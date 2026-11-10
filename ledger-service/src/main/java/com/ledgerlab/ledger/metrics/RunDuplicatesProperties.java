package com.ledgerlab.ledger.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.run-duplicates")
public record RunDuplicatesProperties(Duration idleRetention) {
}
