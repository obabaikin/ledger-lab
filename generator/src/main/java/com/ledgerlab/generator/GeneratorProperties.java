package com.ledgerlab.generator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("generator")
public record GeneratorProperties(
    String gatewayUrl,
    Duration connectTimeout,
    Duration requestTimeout,
    // Attempts per transfer, the first one included; past that the outcome stays unknown (ADR 0006).
    int maxAttempts,
    // Pause before the second attempt; it doubles for each further one, with jitter.
    Duration retryBackoff,
    int maxInFlight,
    Duration maxDuration,
    Duration retention,
    JournalProperties journal,
    ProfileProperties profile
) {
}
