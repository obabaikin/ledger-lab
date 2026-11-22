package com.ledgerlab.ledger.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.consumer")
public record ConsumerProperties(Duration retryBackoff, Duration assignmentWait) {
}
