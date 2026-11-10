package com.ledgerlab.ledger.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.results")
public record ResultsProperties(
    String topic,
    int batchSize,
    Duration sendTimeout
) {
}
