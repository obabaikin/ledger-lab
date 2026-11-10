package com.ledgerlab.ledger.kafka;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.consumer")
public record ConsumerProperties(Duration retryBackoff, @Nullable String instanceId) {
}
