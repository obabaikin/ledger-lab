package com.ledgerlab.gateway.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;

/**
 * A histogram rather than percentiles: buckets add up over the gateway's instances and over any minute, and a bucket at
 * each threshold counts the transfers within it exactly (docs/guides/slo.md).
 */
public final class TransferLatency {

    public static final String NAME = "gateway.transfer.latency";
    private static final Duration SHORTEST = Duration.ofMillis(1);
    private static final Duration LONGEST = Duration.ofSeconds(60);

    private TransferLatency() {
    }

    public static Timer timer(MeterRegistry registry, List<Duration> sloThresholds) {
        return Timer.builder(NAME)
            .description("accepted at the edge to result known")
            .publishPercentileHistogram()
            .minimumExpectedValue(SHORTEST)
            .maximumExpectedValue(LONGEST)
            .serviceLevelObjectives(sloThresholds.toArray(Duration[]::new))
            .register(registry);
    }
}
