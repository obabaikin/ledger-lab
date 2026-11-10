package com.ledgerlab.ledger.chaos;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.chaos")
public record ChaosProperties(Duration haltDelay) {
}
