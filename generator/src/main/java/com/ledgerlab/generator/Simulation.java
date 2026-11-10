package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * One load run. A pacer launches {@code targetTps} submissions per second, spread evenly; each
 * submission is a virtual thread over a blocking HTTP call, so slow responses do not slow the
 * pace. The semaphore caps how many requests may be open at once.
 */
public final class Simulation {

    private static final Duration PACER_TICK = Duration.ofMillis(1);
    /** A send that ended without an answer, possibly after the request went out: it cannot count as failed. */
    private static final SubmissionResult CUT_SHORT =
        new SubmissionResult(null, null, GatewayClient.FIRST_ATTEMPT, SubmissionOutcome.UNKNOWN, Duration.ZERO, 0);
    private static final Duration DRAIN_POLL = Duration.ofMillis(10);
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final double NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1);
    private static final double P50 = 0.5;
    private static final double P99 = 0.99;
    /** Same percentiles as the bank's services publish, so dashboards can put them side by side. */
    private static final double[] LATENCY_PERCENTILES = {0.5, 0.95, 0.99};

    private static final Logger log = LoggerFactory.getLogger(Simulation.class);

    private final UUID runId;
    private final int targetTps;
    private final Duration duration;
    private final LoadProfile profile;
    // Share of submissions that wait for the ledger's result, to measure end-to-end latency.
    private final double waitShare;
    private final Duration waitTimeout;
    private final Duration stopGrace;
    private final GatewayClient client;
    private final double duplicateShare;
    private final List<Duration> duplicateDelays;

    // Submissions are not started by the pacer's own thread pool, so stopping the pacer leaves open requests running.
    private final ExecutorService submissions = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight;

    private final LongAdder requested = new LongAdder();
    private final AtomicLongArray requestedPerSecond;
    private final LongAdder acknowledged = new LongAdder();
    private final LongAdder posted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final ConcurrentHashMap<RejectReason, LongAdder> rejectedByReason = new ConcurrentHashMap<>();
    private final LongAdder failed = new LongAdder();
    private final LongAdder unknown = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private final LongAdder duplicatesSent = new LongAdder();
    private final AtomicLong open = new AtomicLong();
    /** How long a user waited for a final answer to one press, retries and their pauses included. */
    private final Map<SendKind, Timer> submissionLatency = new EnumMap<>(SendKind.class);
    /** The same first presses as {@link #submissionLatency}, kept whole, so the run's report gets exact percentiles. */
    private final ConcurrentLinkedQueue<Long> waitedLatenciesNanos = new ConcurrentLinkedQueue<>();
    /** One point per answered send; a histogram, so percentiles can be taken over any mix of tags. */
    private final Map<SendTimerKey, Timer> sendLatency = new HashMap<>();
    private volatile @Nullable Instant startedAt = null;
    private long startNanos = 0L;
    /** Created at start; released some time after the run ends, while the status stays. Dropping the reference is what frees the memory. */
    private volatile @Nullable Journal journal = null;
    private final long journalCapacity;
    private volatile @Nullable Instant finishedAt = null;
    private volatile SimulationState state = SimulationState.RUNNING;
    /** Opens when the run leaves RUNNING, for a duplicate that waits for its moment. */
    private final CountDownLatch noLongerRunning = new CountDownLatch(1);

    private volatile @Nullable Thread pacer = null;

    private record SendTimerKey(boolean waited, SendKind kind, boolean isFinal) {
    }

    private record Answer(SubmissionResult result, long endedNanos) {
    }

    public Simulation(
        UUID runId,
        int targetTps,
        Duration duration,
        LoadProfile profile,
        double waitShare,
        Duration waitTimeout,
        Duration stopGrace,
        GatewayClient client,
        int maxInFlight,
        MeterRegistry registry,
        double duplicateShare,
        List<Duration> duplicateDelays
    ) {
        if (duplicateShare != 0.0 && duplicateDelays.isEmpty()) {
            throw new IllegalArgumentException("duplicates need at least one delay");
        }
        this.runId = runId;
        this.targetTps = targetTps;
        this.duration = duration;
        this.profile = profile;
        this.waitShare = waitShare;
        this.waitTimeout = waitTimeout;
        this.stopGrace = stopGrace;
        this.client = client;
        this.duplicateShare = duplicateShare;
        this.duplicateDelays = duplicateDelays;
        this.inFlight = new Semaphore(maxInFlight);
        this.requestedPerSecond = new AtomicLongArray((int) duration.toSeconds() + 1);
        this.journalCapacity = journalCapacity(targetTps, duration);
        for (var kind : List.of(SendKind.FIRST, SendKind.DUPLICATE)) {
            submissionLatency.put(kind, Timer.builder("generator.submission.latency").tag("waited", "true").tag("kind", kind.tag())
                .publishPercentiles(LATENCY_PERCENTILES).register(registry));
        }
        for (var waited : List.of(true, false)) {
            for (var kind : SendKind.values()) {
                for (var isFinal : List.of(true, false)) {
                    sendLatency.put(
                        new SendTimerKey(waited, kind, isFinal),
                        Timer.builder("generator.send.latency")
                            .tag("waited", Boolean.toString(waited)).tag("kind", kind.tag()).tag("final", Boolean.toString(isFinal))
                            .publishPercentileHistogram().register(registry));
                }
            }
        }
    }

    public static long journalCapacity(int targetTps, Duration duration) {
        return (long) targetTps * duration.toSeconds();
    }

    public UUID runId() {
        return runId;
    }

    public Duration stopGrace() {
        return stopGrace;
    }

    public Instant startedAt() {
        return Objects.requireNonNull(startedAt, "the run was not started");
    }

    public @Nullable Journal journal() {
        return journal;
    }

    public long journalCapacity() {
        return journalCapacity;
    }

    public @Nullable Instant finishedAt() {
        return finishedAt;
    }

    public SimulationState state() {
        return state;
    }

    /** The run's clock starts here, not at construction, so the first tick has no backlog to catch up. */
    public void start() {
        startedAt = Instant.now();
        startNanos = System.nanoTime();
        journal = new Journal(startNanos);
        pacer = Thread.ofVirtual().start(this::pace);
    }

    private void pace() {
        var intervalNanos = NANOS_PER_SECOND / targetTps;
        var endNanos = startNanos + duration.toNanos();
        var launched = 0L;
        try {
            // The interval is rounded down, so without the capacity check one more could be due before the end.
            while (!Thread.currentThread().isInterrupted() && System.nanoTime() < endNanos && launched < journalCapacity) {
                var due = Math.min((System.nanoTime() - startNanos) / intervalNanos + 1, journalCapacity);
                while (launched < due && System.nanoTime() < endNanos) {
                    // A stop must not wait for the pacer to catch up with what is due.
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                    launched++;
                    inFlight.acquire();
                    launch();
                }
                Thread.sleep(PACER_TICK);
            }
            awaitDrained();
            transition(SimulationState.RUNNING, SimulationState.FINISHED);
        } catch (InterruptedException e) {
            // A stop interrupts the pacer and takes the run from here.
            Thread.currentThread().interrupt();
        }
    }

    private void launch() {
        open.incrementAndGet();
        try {
            // The task always starts, so its finally always runs, also for a submission launched just before a stop.
            submissions.execute(() -> {
                try {
                    submitOne();
                } finally {
                    open.decrementAndGet();
                    inFlight.release();
                }
            });
        } catch (RuntimeException e) {
            open.decrementAndGet();
            inFlight.release();
            throw e;
        }
    }

    /**
     * Stops the client side only: no new submissions, no retries. Requests already sent get
     * {@link #stopGrace()} to receive their answer; whatever is left after that is cancelled and counted
     * as unknown.
     */
    public void stop() {
        if (!transition(SimulationState.RUNNING, SimulationState.STOPPING)) {
            return;
        }
        Thread.ofVirtual().start(this::finishStop);
    }

    private void finishStop() {
        try {
            // Until the pacer has finished, it may still be counting a submission it was launching.
            var pacing = pacer;
            if (pacing != null) {
                pacing.interrupt();
                pacing.join();
            }
            var deadline = System.nanoTime() + stopGrace.toNanos();
            while (open.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(DRAIN_POLL);
            }
            if (open.get() > 0) {
                submissions.shutdownNow();
                while (!submissions.awaitTermination(stopGrace.toNanos() + NANOS_PER_SECOND, TimeUnit.NANOSECONDS)) {
                    log.warn("Simulation {} still has {} submissions that do not end", runId, open.get());
                }
            }
            transition(SimulationState.STOPPING, SimulationState.STOPPED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitDrained() throws InterruptedException {
        while (open.get() > 0) {
            Thread.sleep(DRAIN_POLL);
        }
    }

    private synchronized boolean transition(SimulationState from, SimulationState to) {
        if (state != from) {
            return false;
        }
        state = to;
        noLongerRunning.countDown();
        if (!to.isActive()) {
            finishedAt = Instant.now();
            log.info(
                "Simulation {} {}: requested={} acknowledged={} failed={} unknown={}",
                runId, to, requested.sum(), acknowledged.sum(), failed.sum(), unknown.sum());
        }
        return true;
    }

    private void submitOne() {
        // A submission launched just before a stop may start after it; it must not send.
        if (state != SimulationState.RUNNING) {
            return;
        }
        var transfer = profile.next();
        var entry = Objects.requireNonNull(journal, "journal released while the run is active").add(transfer);
        // Counted only once the entry exists, so that every request ends up in exactly one outcome.
        requested.increment();
        var second = Math.clamp((System.nanoTime() - startNanos) / NANOS_PER_SECOND, 0, requestedPerSecond.length() - 1);
        requestedPerSecond.incrementAndGet(second);
        var random = ThreadLocalRandom.current();
        var wait = random.nextDouble() < waitShare ? waitTimeout : null;
        var duplicateDelay = random.nextDouble() < duplicateShare ? duplicateDelays.get(random.nextInt(duplicateDelays.size())) : null;
        // Collected as each send ends, so a 2xx already received still counts when the other send is cut short.
        var answers = new ConcurrentLinkedQueue<Answer>();
        var duplicate = duplicateDelay == null ? null : Thread.ofVirtual().start(() -> {
            var answer = sendDuplicate(transfer, wait, entry, duplicateDelay);
            if (answer != null) {
                answers.add(answer);
            }
        });
        var cutShort = false;
        var interrupted = false;
        try {
            var main = client.submit(transfer, wait, () -> state == SimulationState.RUNNING, recorder(entry, wait != null, false));
            answers.add(new Answer(main, System.nanoTime()));
            retries.add(main.attempts() - GatewayClient.FIRST_ATTEMPT);
            recordSubmissionLatency(main, wait, SendKind.FIRST);
        } catch (InterruptedException e) {
            cutShort = true;
            interrupted = true;
        } catch (RuntimeException e) {
            cutShort = true;
            log.warn("Submission in simulation {} ended with an error", runId, e);
        }
        if (duplicate != null) {
            // A first send that did not end well takes its duplicate with it; both have ended before the answers are put together.
            if (cutShort) {
                duplicate.interrupt();
            }
            while (duplicate.isAlive()) {
                try {
                    duplicate.join();
                } catch (InterruptedException e) {
                    cutShort = true;
                    interrupted = true;
                    duplicate.interrupt();
                }
            }
        }
        countRefused(answers);
        var results = inOrder(answers);
        if (cutShort) {
            results.add(CUT_SHORT);
        }
        settle(entry, OutcomeRule.combine(results));
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void countRefused(Collection<Answer> answers) {
        if (answers.stream().anyMatch(it -> it.result().refusals() > 0)) {
            refused.increment();
        }
    }

    private static List<SubmissionResult> inOrder(Collection<Answer> answers) {
        var results = new ArrayList<SubmissionResult>();
        answers.stream().sorted(Comparator.comparingLong(Answer::endedNanos)).forEach(it -> results.add(it.result()));
        return results;
    }

    private @Nullable Answer sendDuplicate(PlannedTransfer transfer, @Nullable Duration wait, JournalEntry entry, Duration delay) {
        try {
            var stopped = noLongerRunning.await(delay.toMillis(), TimeUnit.MILLISECONDS);
            if (stopped || state != SimulationState.RUNNING) {
                return null;
            }
            duplicatesSent.increment();
            SubmissionResult result;
            try {
                result = client.submit(transfer, wait, () -> false, recorder(entry, wait != null, true));
            } catch (RuntimeException e) {
                // Kept to this send, so it cannot cancel the first send and lose an answer that one gets.
                log.warn("Duplicate in simulation {} ended with an error", runId, e);
                result = CUT_SHORT;
            }
            recordSubmissionLatency(result, wait, SendKind.DUPLICATE);
            return new Answer(result, System.nanoTime());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private SendRecorder recorder(JournalEntry entry, boolean waited, boolean duplicate) {
        return (attempt, startedNanos, endedNanos, result, httpStatus) -> {
            SendKind kind;
            if (duplicate) {
                kind = SendKind.DUPLICATE;
            } else if (attempt == GatewayClient.FIRST_ATTEMPT) {
                kind = SendKind.FIRST;
            } else {
                kind = SendKind.RETRY;
            }
            entry.record(kind, startedNanos, endedNanos, result, httpStatus);
            if (httpStatus != null && HttpStatusCode.valueOf(httpStatus).is2xxSuccessful()) {
                var timer = sendLatency.get(new SendTimerKey(waited, kind, httpStatus == HttpStatus.OK.value()));
                Objects.requireNonNull(timer, "a timer for every kind of send").record(endedNanos - startedNanos, TimeUnit.NANOSECONDS);
            }
        };
    }

    private void recordSubmissionLatency(SubmissionResult result, @Nullable Duration wait, SendKind kind) {
        var status = result.status();
        if (wait != null && result.outcome() == SubmissionOutcome.ACKNOWLEDGED && status != null && status.isFinal()) {
            Objects.requireNonNull(submissionLatency.get(kind), "a timer for first sends and duplicates").record(result.latency());
            if (kind == SendKind.FIRST) {
                waitedLatenciesNanos.add(result.latency().toNanos());
            }
        }
    }

    private void settle(JournalEntry entry, SubmissionResult result) {
        switch (result.outcome()) {
            case FAILED -> {
                failed.increment();
                entry.settle(JournalOutcome.FAILED);
            }
            case UNKNOWN -> {
                unknown.increment();
                entry.settle(JournalOutcome.UNKNOWN);
            }
            case ACKNOWLEDGED -> {
                acknowledged.increment();
                var status = result.status();
                if (status != null) {
                    switch (status) {
                        case POSTED -> posted.increment();
                        case REJECTED -> {
                            rejected.increment();
                            var reason = result.rejectReason();
                            if (reason != null) {
                                rejectedByReason.computeIfAbsent(reason, key -> new LongAdder()).increment();
                            }
                        }
                        case ACCEPTED, PUBLISHED -> {
                        }
                    }
                }
                entry.settle(JournalOutcome.ACKNOWLEDGED, status, result.rejectReason());
            }
        }
    }

    public boolean finishedBefore(Instant instant) {
        var finished = finishedAt;
        return finished != null && finished.isBefore(instant);
    }

    public void releaseJournal() {
        if (state.isActive()) {
            throw new IllegalStateException("journal of an active run cannot be released");
        }
        journal = null;
    }

    public SimulationStatus status() {
        var started = startedAt();
        var finished = finishedAt;
        var end = finished != null ? finished : Instant.now();
        var seconds = (double) Math.max(Duration.between(started, end).toNanos(), 1) / NANOS_PER_SECOND;
        var sent = requested.sum();
        var waited = waitedLatenciesNanos.stream().sorted().toList();
        var byReason = new LinkedHashMap<RejectReason, Long>();
        rejectedByReason.forEach((reason, count) -> byReason.put(reason, count.sum()));
        var perSecond = new ArrayList<Long>();
        for (var i = 0; i < secondsSoFar(started, end); i++) {
            perSecond.add(requestedPerSecond.get(i));
        }
        return new SimulationStatus(
            runId, state, targetTps, (int) duration.toSeconds(), started, finished,
            sent, acknowledged.sum(), posted.sum(), rejected.sum(),
            byReason, failed.sum(), unknown.sum(), retries.sum(), refused.sum(),
            duplicatesSent.sum(), open.get(), sent / seconds,
            waitedLatencyMs(waited, P50), waitedLatencyMs(waited, P99),
            perSecond);
    }

    private int secondsSoFar(Instant started, Instant end) {
        return Math.clamp(Duration.between(started, end).toSeconds() + 1, 0, requestedPerSecond.length());
    }

    private static @Nullable Double waitedLatencyMs(List<Long> sorted, double p) {
        var nanos = Percentiles.percentileCont(sorted, p);
        return nanos == null ? null : nanos / NANOS_PER_MILLI;
    }
}
