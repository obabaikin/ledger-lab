package com.ledgerlab.gateway.api;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.persistence.RunPeriodRepository;
import com.ledgerlab.gateway.persistence.TransferRepository;
import com.ledgerlab.gateway.service.TransferAdmission;
import com.ledgerlab.gateway.service.TransferBatcher;
import com.ledgerlab.gateway.service.TransferRequest;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the submission path before the gateway takes runs: a cold JIT answered the first seconds of a run after a
 * deploy with 503 busy. Every round is rolled back, so nothing reaches the transfers, the outbox or Kafka. The gateway
 * takes traffic after the short limit, so a deploy does not wait; control admits runs only once {@link #WARM} reads 1.
 */
@Component
public class TransferWarmUp implements ApplicationRunner {

    /** 1 once the rounds are done or their limit passed; control reads it from Prometheus. */
    public static final String WARM = "gateway.warm_up.complete";
    private static final int NOT_WARM = 0;
    private static final int WARM_VALUE = 1;
    private static final Duration RESULT_MARGIN = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(TransferWarmUp.class);

    private final TransferRepository transfers;
    private final RunPeriodRepository periods;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ApplicationEventPublisher events;
    private final GatewayProperties props;
    private final AtomicInteger warm = new AtomicInteger(NOT_WARM);

    public TransferWarmUp(
        TransferRepository transfers,
        RunPeriodRepository periods,
        PlatformTransactionManager transactions,
        ObjectMapper objectMapper,
        Validator validator,
        ApplicationEventPublisher events,
        GatewayProperties props,
        MeterRegistry registry
    ) {
        this.transfers = transfers;
        this.periods = periods;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.events = events;
        this.props = props;
        registry.gauge(WARM, warm);
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        warmUp();
    }

    /** Takes traffic after the short limit in any case: a gateway that cannot warm up still serves, only slower at first. */
    public void warmUp() throws InterruptedException {
        warm.set(NOT_WARM);
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        var started = System.nanoTime();
        // Not closed with use: closing waits for the round, and a round stuck on the database must not hold the start.
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var rounds = executor.submit(() -> rounds(started + props.warmUp().maxDuration().toNanos()));
        try {
            report(rounds.get(props.warmUp().duration().toNanos(), TimeUnit.NANOSECONDS), started);
            finish(executor);
        } catch (TimeoutException e) {
            log.info("Warm-up takes traffic after {}; the rounds go on for up to {}", props.warmUp().duration(), props.warmUp().maxDuration());
            executor.execute(() -> {
                try {
                    awaitBehind(rounds, started, executor);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (ExecutionException e) {
            log.warn("Warm-up failed", e.getCause());
            finish(executor);
        }
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    private void awaitBehind(Future<Integer> rounds, long started, ExecutorService executor) throws InterruptedException {
        try {
            // The rounds stop at the limit themselves; the margin lets the last one return, so a timeout means a stuck round.
            var left = started + props.warmUp().maxDuration().plus(RESULT_MARGIN).toNanos() - System.nanoTime();
            report(rounds.get(left, TimeUnit.NANOSECONDS), started);
        } catch (TimeoutException e) {
            log.warn("Warm-up did not finish a round within its limit of {}", props.warmUp().maxDuration());
        } catch (ExecutionException e) {
            log.warn("Warm-up failed", e.getCause());
        } finally {
            finish(executor);
        }
    }

    private void report(int done, long started) {
        if (done < props.warmUp().rounds()) {
            log.warn("Warm-up reached its limit of {} after {} of {} rounds", props.warmUp().maxDuration(), done, props.warmUp().rounds());
        } else {
            log.info("Warm-up: {} rounds in {} ms", done, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /** Warm also when the rounds failed or got stuck: runs must not wait for a gateway that will never get warmer. */
    private void finish(ExecutorService executor) {
        warm.set(WARM_VALUE);
        executor.shutdownNow();
    }

    private int rounds(long deadline) throws InterruptedException {
        // Instances of its own count into a registry nobody reads: the rounds are not transfers.
        var registry = new SimpleMeterRegistry();
        var service = new TransferService(transfers, periods, registry);
        var rolledBack = new TransactionTemplate(transactions);
        var batcher = new TransferBatcher(
            requests -> Objects.requireNonNull(
                rolledBack.execute(status -> {
                    status.setRollbackOnly();
                    return service.acceptAll(requests);
                }),
                "outcomes of a warm-up batch"),
            props,
            registry);
        var admission = new TransferAdmission(props, registry);
        batcher.start();
        try {
            var done = 0;
            while (done < props.warmUp().rounds() && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                round(batcher, admission);
                done++;
            }
            return done;
        } finally {
            batcher.stop();
        }
    }

    private void round(TransferBatcher batcher, TransferAdmission admission) throws InterruptedException {
        var body = objectMapper.writeValueAsString(new SubmitTransferRequest(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 1));
        var request = objectMapper.readValue(body, SubmitTransferRequest.class);
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalStateException("the warm-up request is invalid");
        }
        var transfer = admission.admit(() -> batcher.accept(new TransferRequest(
            Objects.requireNonNull(request.transferId(), "transferId of the warm-up request"),
            Objects.requireNonNull(request.runId(), "runId of the warm-up request"),
            request.fromAccount(),
            request.toAccount(),
            request.amountCents())));
        objectMapper.writeValueAsString(TransferResponse.of(transfer));
    }
}
