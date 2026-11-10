package com.ledgerlab.reporting;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reporting")
public record ReportingProperties(
    String transfersTopic,
    String resultsTopic,
    Consumer consumer
) {
    public record Consumer(Duration retryBackoff) {
    }
}
