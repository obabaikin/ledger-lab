package com.ledgerlab.control.metrics;

import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.generator.SimulationStatus;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.reporting.ReportingClient;
import com.ledgerlab.control.reporting.RunStats;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class LiveRuns {

    /** Set by the chart's Prometheus config on the project's own services, not on the exporters beside them. */
    private static final String UP = "up{lab_service=\"true\"}";
    private static final String PARTITIONS = LedgerMetrics.ASSIGNED_PARTITIONS;
    private static final String DUPLICATES = "sum by (run) (" + LedgerMetrics.DUPLICATES + ")";
    private static final String INSTANCE = "instance";
    private static final String JOB = "job";
    private static final String RUN = "run";
    private static final double UP_VALUE = 1.0;

    private final ReportingClient reporting;
    private final GeneratorClient generator;
    private final PrometheusClient prometheus;
    private final ConcurrentHashMap<UUID, LiveView> views = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> duplicatesSeen = new ConcurrentHashMap<>();

    private record Target(String job, String instance) {
    }

    private record RunRead(@Nullable RunStats stats, @Nullable ClientCounts clients) {
    }

    public LiveRuns(ReportingClient reporting, GeneratorClient generator, PrometheusClient prometheus) {
        this.reporting = reporting;
        this.generator = generator;
        this.prometheus = prometheus;
    }

    public @Nullable LiveView of(UUID runId) {
        return views.get(runId);
    }

    public @Nullable Long ledgerDuplicatesAtLeast(UUID runId) {
        return duplicatesSeen.get(runId);
    }

    public void refresh(Collection<UUID> runIds) throws InterruptedException, ExecutionException {
        var current = new HashSet<>(runIds);
        views.keySet().retainAll(current);
        duplicatesSeen.keySet().retainAll(current);
        if (runIds.isEmpty()) {
            return;
        }
        try (var reads = Executors.newVirtualThreadPerTaskExecutor()) {
            var pods = reads.submit(this::pods);
            var duplicates = reads.submit(this::duplicates);
            var perRun = new LinkedHashMap<UUID, Future<RunRead>>();
            for (var runId : runIds) {
                perRun.put(runId, reads.submit(() -> new RunRead(stats(runId), clients(runId))));
            }
            var podsNow = pods.get();
            // A run without a series has no duplicates yet: the ledger registers its counter on the first one.
            var counts = duplicates.get();
            if (counts != null) {
                runIds.forEach(runId -> duplicatesSeen.merge(runId, counts.getOrDefault(runId, 0L), Math::max));
            }
            var now = Instant.now();
            for (var read : perRun.entrySet()) {
                var runId = read.getKey();
                var fresh = read.getValue().get();
                views.compute(runId, (id, last) -> new LiveView(
                    fresh.stats() != null || last == null ? fresh.stats() : last.stats(),
                    fresh.clients() != null || last == null ? fresh.clients() : last.clients(),
                    podsNow != null || last == null ? podsNow : last.pods(),
                    duplicatesSeen.get(runId),
                    now,
                    List.of()));
            }
        }
    }

    private @Nullable RunStats stats(UUID runId) throws InterruptedException {
        return reporting.stats(runId) instanceof Reply.Answered<RunStats>(var status, var body) ? body : null;
    }

    private @Nullable ClientCounts clients(UUID runId) throws InterruptedException {
        if (!(generator.status(runId) instanceof Reply.Answered<SimulationStatus>(var status, var it)) || it == null) {
            return null;
        }
        return new ClientCounts(
            it.requested(), it.acknowledged(), it.failed(), it.unknown(), it.retries(), it.refused(), it.duplicatesSent(), it.requestedPerSecond());
    }

    private @Nullable List<PodView> pods() throws InterruptedException {
        var up = prometheus.query(UP);
        if (up == null) {
            return null;
        }
        var partitions = new HashMap<Target, Integer>();
        var held = prometheus.query(PARTITIONS);
        if (held != null) {
            held.forEach(it -> partitions.put(target(it), (int) Math.round(it.number())));
        }
        return up.stream()
            .map(sample -> {
                var target = target(sample);
                return new PodView(target.job(), target.instance(), sample.number() == UP_VALUE, partitions.get(target));
            })
            .sorted(Comparator.comparing(PodView::job).thenComparing(PodView::instance))
            .toList();
    }

    private static Target target(PrometheusSample sample) {
        return new Target(sample.metric().getOrDefault(JOB, ""), sample.metric().getOrDefault(INSTANCE, ""));
    }

    /** A sum over the instances alive now; the most ever seen is kept, as an instance that dies takes its count along. */
    private @Nullable Map<UUID, Long> duplicates() throws InterruptedException {
        var samples = prometheus.query(DUPLICATES);
        if (samples == null) {
            return null;
        }
        var counts = new HashMap<UUID, Long>();
        for (var sample : samples) {
            var run = sample.metric().get(RUN);
            var runId = run == null ? null : runIdOf(run);
            if (runId != null) {
                counts.put(runId, Math.round(sample.number()));
            }
        }
        return counts;
    }

    private static @Nullable UUID runIdOf(String label) {
        try {
            return UUID.fromString(label);
        } catch (Throwable e) {
            return null;
        }
    }
}
