package com.ledgerlab.ledger.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redeliveries the ledger skipped, per run: not in any event, so only monitoring sees them (ADR 0015).
 * A lower bound: what a killed instance counted is gone with it.
 */
@Component
public class RunDuplicates {

    public static final String METRIC = "ledger.duplicates";
    public static final String RUN_TAG = "run";

    private static final Logger log = LoggerFactory.getLogger(RunDuplicates.class);

    private final MeterRegistry registry;
    private final RunDuplicatesProperties props;
    private final ConcurrentHashMap<UUID, Tracked> counters = new ConcurrentHashMap<>();

    private record Tracked(Counter counter, long lastNanos) {
    }

    public RunDuplicates(MeterRegistry registry, RunDuplicatesProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** Never fails the caller: the transfer is already handled, a lost count only lowers a lower bound. */
    public void record(UUID runId) {
        try {
            // Under the run's entry, so a sweep cannot remove the counter between its lookup and its increment.
            counters.compute(runId, (key, tracked) -> {
                var counter = tracked != null
                    ? tracked.counter()
                    : Counter.builder(METRIC).tag(RUN_TAG, runId.toString()).register(registry);
                counter.increment();
                return new Tracked(counter, System.nanoTime());
            });
        } catch (Throwable e) {
            log.warn("Duplicate of run {} not counted", runId, e);
        }
    }

    @Scheduled(fixedDelayString = "${ledger.run-duplicates.sweep-interval}")
    public void removeIdle() {
        var now = System.nanoTime();
        for (var runId : counters.keySet()) {
            counters.computeIfPresent(runId, (key, tracked) -> {
                if (now - tracked.lastNanos() < props.idleRetention().toNanos()) {
                    return tracked;
                }
                registry.remove(tracked.counter());
                return null;
            });
        }
    }
}
