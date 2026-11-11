package com.ledgerlab.control.bank;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.generator.LoadStatus;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.ledger.Books;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.metrics.BankMetrics;
import com.ledgerlab.control.metrics.PodView;
import com.ledgerlab.control.metrics.PrometheusClient;
import com.ledgerlab.control.metrics.PrometheusSample;
import com.ledgerlab.control.reporting.PeriodStats;
import com.ledgerlab.control.reporting.ReportingClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The bank's numbers of this moment, read every tick; each part is the last one read and keeps it while its source cannot be read. */
@Component
public class BankLive {

    private static final String INSTANCE = "instance";
    private static final String JOB = "job";
    private static final double UP_VALUE = 1.0;

    /** {@link #stats} holds the periods not reconciled yet, by their start. */
    public record Snapshot(
        @Nullable LoadStatus clients,
        @Nullable List<PodView> pods,
        @Nullable Books books,
        @Nullable Instant booksReadAt,
        Map<Instant, PeriodStats> stats,
        @Nullable Instant readAt
    ) {
        static final Snapshot EMPTY = new Snapshot(null, null, null, null, Map.of(), null);
    }

    private record Target(String job, String instance) {
    }

    private final ReportingClient reporting;
    private final GeneratorClient generator;
    private final LedgerClient ledger;
    private final PrometheusClient prometheus;
    private final Duration booksInterval;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public BankLive(ReportingClient reporting, GeneratorClient generator, LedgerClient ledger, PrometheusClient prometheus, ControlProperties props) {
        this.reporting = reporting;
        this.generator = generator;
        this.ledger = ledger;
        this.prometheus = prometheus;
        this.booksInterval = props.bank().booksInterval();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void refresh(Instant now, List<Instant> unfinishedPeriods) throws InterruptedException, ExecutionException {
        var last = snapshot;
        try (var reads = Executors.newVirtualThreadPerTaskExecutor()) {
            var pods = reads.submit(this::pods);
            var clients = reads.submit(this::clients);
            var booksDue = last.booksReadAt() == null || !now.isBefore(last.booksReadAt().plus(booksInterval));
            var books = booksDue ? reads.submit(this::books) : null;
            var perPeriod = new LinkedHashMap<Instant, Future<@Nullable PeriodStats>>();
            for (var period : unfinishedPeriods) {
                perPeriod.put(period, reads.submit(() -> stats(period)));
            }
            var stats = new LinkedHashMap<Instant, PeriodStats>();
            for (var read : perPeriod.entrySet()) {
                var fresh = read.getValue().get();
                var kept = fresh != null ? fresh : last.stats().get(read.getKey());
                if (kept != null) {
                    stats.put(read.getKey(), kept);
                }
            }
            var podsNow = pods.get();
            var clientsNow = clients.get();
            var booksNow = books == null ? null : books.get();
            snapshot = new Snapshot(
                clientsNow != null ? clientsNow : last.clients(),
                podsNow != null ? podsNow : last.pods(),
                booksNow != null ? booksNow : last.books(),
                booksNow != null ? now : last.booksReadAt(),
                Map.copyOf(stats),
                now);
        }
    }

    private @Nullable PeriodStats stats(Instant periodStart) throws InterruptedException {
        return reporting.stats(periodStart) instanceof Reply.Answered<PeriodStats>(var status, var body) ? body : null;
    }

    private @Nullable LoadStatus clients() throws InterruptedException {
        return generator.load() instanceof Reply.Answered<LoadStatus>(var status, var body) ? body : null;
    }

    private @Nullable Books books() throws InterruptedException {
        return ledger.books() instanceof Reply.Answered<Books>(var status, var body) ? body : null;
    }

    private @Nullable List<PodView> pods() throws InterruptedException {
        var up = prometheus.query(BankMetrics.UP);
        if (up == null) {
            return null;
        }
        var partitions = new HashMap<Target, Integer>();
        var held = prometheus.query(BankMetrics.ASSIGNED_PARTITIONS);
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
}
