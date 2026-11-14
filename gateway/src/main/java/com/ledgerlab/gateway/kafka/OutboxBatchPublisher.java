package com.ledgerlab.gateway.kafka;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.persistence.TransferRepository;
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
 * One batch per transaction: claim with SKIP LOCKED, send, mark published. Several gateway
 * replicas can run this concurrently without publishing the same row twice; a crash between
 * send and mark re-sends the batch, which the ledger's idempotency absorbs.
 */
@Component
public class OutboxBatchPublisher {

    public static final String AGE_METRIC = "gateway.outbox.age";

    private static final Logger log = LoggerFactory.getLogger(OutboxBatchPublisher.class);

    private final TransferRepository transfers;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final GatewayProperties props;
    private final Clock clock;
    private final Timer age;

    public OutboxBatchPublisher(
        TransferRepository transfers,
        KafkaTemplate<String, String> kafka,
        ObjectMapper objectMapper,
        GatewayProperties props,
        Clock clock,
        MeterRegistry registry
    ) {
        this.transfers = transfers;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.props = props;
        this.clock = clock;
        this.age = Timer.builder(AGE_METRIC)
            .description("How long the oldest command of a batch waited in the outbox; growing, the publisher falls behind")
            .register(registry);
    }

    @Transactional
    public int publishOne() throws InterruptedException, ExecutionException, TimeoutException {
        var commands = transfers.claimUnpublished(props.outbox().batchSize());
        if (commands.isEmpty()) {
            return 0;
        }
        age.record(Duration.between(commands.getFirst().acceptedAt(), clock.instant()));
        var sends = commands.stream()
            .map(command -> kafka.send(props.transfersTopic(), command.partitionKey(), objectMapper.writeValueAsString(command)))
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).get(props.outbox().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        transfers.markPublished(commands);
        log.debug("Published {} transfer commands", commands.size());
        return commands.size();
    }
}
