package com.ledgerlab.ledger.kafka;

import com.ledgerlab.ledger.persistence.ResultOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * One batch per transaction: claim rows with SKIP LOCKED, send, mark published.
 * A crash between send and mark re-sends the batch (at-least-once); consumers are idempotent.
 */
@Component
public class ResultBatchPublisher {

    public static final String AGE_METRIC = "ledger.results.age";

    private static final Logger log = LoggerFactory.getLogger(ResultBatchPublisher.class);

    private final ResultOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final ResultsProperties results;
    private final Clock clock;
    private final Timer age;

    public ResultBatchPublisher(
        ResultOutboxRepository outbox,
        KafkaTemplate<String, String> kafka,
        ObjectMapper objectMapper,
        ResultsProperties results,
        Clock clock,
        MeterRegistry registry
    ) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.results = results;
        this.clock = clock;
        this.age = Timer.builder(AGE_METRIC)
            .description("How long the oldest result of a batch waited in the outbox; growing, the publisher falls behind")
            .register(registry);
    }

    @Transactional
    public int publishOne() throws InterruptedException, ExecutionException, TimeoutException {
        var events = outbox.claimUnpublished(results.batchSize());
        if (events.isEmpty()) {
            return 0;
        }
        age.record(Duration.between(events.getFirst().processedAt(), clock.instant()));
        var sends = events.stream()
            .map(event -> kafka.send(results.topic(), event.partitionKey(), objectMapper.writeValueAsString(event)))
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).get(results.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        outbox.markPublished(events);
        log.debug("Published {} transfer results", events.size());
        return events.size();
    }
}
