package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.domain.Transfer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Submissions wait in one queue; each writer takes what gathered while its last batch committed, so a lone request
 * goes at once and a busy gateway commits many per transaction. The admission limit bounds the queue.
 */
@Component
public class TransferBatcher {

    public static final String WRITER_THREAD = "accept-writer-";
    public static final String COMMIT_METRIC = "gateway.accept.commit";
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);
    private static final double[] BATCH_PERCENTILES = {0.5, 0.99};
    private static final Duration SHORTEST_COMMIT = Duration.ofMillis(1);
    private static final Duration LONGEST_COMMIT = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(TransferBatcher.class);

    private final Function<List<TransferRequest>, List<AcceptOutcome>> writeBatch;
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final int maxBatch;
    private final Duration answerTimeout;
    private final DistributionSummary batchSizes;
    private final Timer commits;
    private final Counter refusedAsStopping;
    private final List<Thread> writers;

    private volatile boolean stopping = false;

    private record Pending(TransferRequest request, CompletableFuture<Transfer> result) {
        Pending(TransferRequest request) {
            this(request, new CompletableFuture<>());
        }
    }

    public TransferBatcher(
        Function<List<TransferRequest>, List<AcceptOutcome>> writeBatch,
        GatewayProperties props,
        MeterRegistry registry
    ) {
        this.writeBatch = writeBatch;
        this.maxBatch = props.accept().batchSize();
        this.answerTimeout = props.accept().answerTimeout();
        this.batchSizes = DistributionSummary.builder("gateway.accept.batch.size")
            .description("submissions committed in one transaction")
            .publishPercentiles(BATCH_PERCENTILES)
            .register(registry);
        this.commits = Timer.builder(COMMIT_METRIC)
            .description("time of one write to the database, from its start to the commit or the failure; a batch whose data was refused adds one per row written again")
            .publishPercentileHistogram()
            .minimumExpectedValue(SHORTEST_COMMIT)
            .maximumExpectedValue(LONGEST_COMMIT)
            .register(registry);
        this.refusedAsStopping = Counter.builder("gateway.transfers").tag("outcome", "stopping").register(registry);
        this.writers = IntStream.range(0, props.accept().writers())
            .mapToObj(index -> Thread.ofPlatform().name(WRITER_THREAD + index).daemon().unstarted(this::write))
            .toList();
    }

    @Autowired
    public TransferBatcher(TransferService service, GatewayProperties props, MeterRegistry registry) {
        this(service::acceptAll, props, registry);
    }

    @PostConstruct
    public void start() {
        writers.forEach(Thread::start);
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        stopping = true;
        writers.forEach(Thread::interrupt);
        for (var writer : writers) {
            writer.join(STOP_WAIT.toMillis());
        }
        for (var pending = queue.poll(); pending != null; pending = queue.poll()) {
            pending.result().completeExceptionally(refusedAsStopping());
        }
    }

    /** Returns after the commit, with the outcome a request of its own would have had. */
    public Transfer accept(TransferRequest request) throws InterruptedException {
        var pending = new Pending(request);
        queue.put(pending);
        // After the put, not before: a stop that emptied the queue just before it would leave this request to its timeout.
        if (stopping && queue.remove(pending)) {
            throw refusedAsStopping();
        }
        try {
            return pending.result().get(answerTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException cause -> throw cause;
                case Error cause -> throw cause;
                case InterruptedException cause -> throw cause;
                case null, default -> throw new UndeclaredThrowableException(e.getCause());
            }
        } catch (TimeoutException e) {
            throw new AcceptTimeoutException(answerTimeout);
        }
    }

    private GatewayStoppingException refusedAsStopping() {
        refusedAsStopping.increment();
        return new GatewayStoppingException();
    }

    private void write() {
        var batch = new ArrayList<Pending>(maxBatch);
        while (true) {
            try {
                batch.add(queue.take());
            } catch (InterruptedException e) {
                return;
            }
            queue.drainTo(batch, maxBatch - 1);
            batchSizes.record(batch.size());
            try {
                write(batch);
            } catch (Throwable e) {
                // A writer that dies leaves its requests and every later one of its queue waiting: answer and go on.
                batch.forEach(it -> it.result().completeExceptionally(e));
                if (e instanceof InterruptedException) {
                    return;
                }
            } finally {
                batch.clear();
            }
        }
    }

    /**
     * A batch whose data the database refused is written again one by one, so that request fails alone. Any other
     * failure (no connection, the database down) fails the whole batch: one by one would only multiply it.
     */
    private void write(List<Pending> batch) {
        List<AcceptOutcome> outcomes;
        try {
            outcomes = timedWrite(batch);
        } catch (DataIntegrityViolationException e) {
            if (batch.size() == 1) {
                batch.getFirst().result().completeExceptionally(e);
            } else {
                log.warn("The database refused the data of a batch of {} transfers; writing them one by one", batch.size(), e);
                batch.forEach(it -> write(List.of(it)));
            }
            return;
        }
        for (var i = 0; i < Math.min(batch.size(), outcomes.size()); i++) {
            var result = batch.get(i).result();
            switch (outcomes.get(i)) {
                case AcceptOutcome.Accepted(var transfer) -> result.complete(transfer);
                case AcceptOutcome.Refused(var cause) -> result.completeExceptionally(cause);
            }
        }
    }

    private List<AcceptOutcome> timedWrite(List<Pending> batch) {
        var started = Timer.start();
        try {
            return writeBatch.apply(batch.stream().map(Pending::request).toList());
        } finally {
            started.stop(commits);
        }
    }
}
