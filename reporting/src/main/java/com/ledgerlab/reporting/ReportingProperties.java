package com.ledgerlab.reporting;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reporting")
public record ReportingProperties(
    String transfersTopic,
    String resultsTopic,
    Consumer consumer,
    Periods periods
) {
    public record Consumer(Duration retryBackoff) {
    }

    /** A period's numbers and the rows of its transfers are held for {@link #retention} after it ended (ADR 0022). */
    public record Periods(
        Duration length,
        Duration retention,
        Duration partitionsAhead,
        Duration maintenanceInterval,
        Duration lockTimeout
    ) {
        /** Partitions are named to the minute of their period's start. */
        public Periods {
            if (length.compareTo(Duration.ofMinutes(1)) < 0 || !length.equals(Duration.ofMinutes(length.toMinutes()))) {
                throw new IllegalArgumentException("reporting.periods.length must be whole minutes, at least one");
            }
        }
    }
}
