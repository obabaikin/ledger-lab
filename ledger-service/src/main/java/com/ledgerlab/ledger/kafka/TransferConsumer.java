package com.ledgerlab.ledger.kafka;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.TransferResult;
import com.ledgerlab.ledger.service.LedgerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public class TransferConsumer {

    public static final String LISTENER_ID = "transfers";
    public static final String DUPLICATES_METRIC = "ledger.duplicates";
    public static final String EXPIRED_METRIC = "ledger.transfers.expired";

    private static final Logger log = LoggerFactory.getLogger(TransferConsumer.class);

    private final LedgerService ledger;
    private final ObjectReader reader;
    private final Counter posted;
    private final Counter rejected;
    private final Counter duplicates;
    private final Counter expired;

    public TransferConsumer(LedgerService ledger, ObjectMapper objectMapper, MeterRegistry registry) {
        this.ledger = ledger;
        this.reader = objectMapper.readerFor(TransferCommand.class);
        this.posted = outcomeCounter(registry, "posted");
        this.rejected = outcomeCounter(registry, "rejected");
        this.duplicates = Counter.builder(DUPLICATES_METRIC)
            .description("Redeliveries the ledger skipped; a lower bound, what a killed instance counted is gone with it (ADR 0015)")
            .register(registry);
        this.expired = Counter.builder(EXPIRED_METRIC)
            .description("Transfers delivered after the ledger dropped their period: not booked, their periods do not reconcile")
            .register(registry);
    }

    // The id lets the readiness check find this container; without idIsGroup = false it would also replace the group id.
    // Started by ConsumerWarmUp, so a new instance joins the group warm.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "${ledger.topic.name}", batch = "true", autoStartup = "false")
    public void onTransfers(List<String> payloads) {
        var commands = new ArrayList<TransferCommand>(payloads.size());
        for (var payload : payloads) {
            var command = parse(payload);
            if (command != null) {
                commands.add(command);
            }
        }
        var results = ledger.applyBatch(commands);
        for (var i = 0; i < commands.size(); i++) {
            count(commands.get(i), results.get(i));
        }
    }

    // In a batch an exception would fail the whole poll; a message that can never be read is skipped on its own.
    private @Nullable TransferCommand parse(String payload) {
        try {
            return reader.readValue(payload);
        } catch (JacksonException e) {
            log.error("Unreadable transfer skipped: {}", payload, e);
            return null;
        }
    }

    private void count(TransferCommand command, TransferResult result) {
        switch (result) {
            case TransferResult.Posted() -> posted.increment();
            case TransferResult.Rejected(var reason) -> rejected.increment();
            case TransferResult.Expired() -> {
                expired.increment();
                log.warn("Transfer {} accepted at {} is older than the ledger holds and was not booked", command.transferId(), command.acceptedAt());
            }
            case TransferResult.AlreadyProcessed() -> {
                duplicates.increment();
                log.debug("Duplicate delivery ignored: transfer={}", command.transferId());
            }
        }
    }

    private static Counter outcomeCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("ledger.transfers").tag("outcome", outcome).register(registry);
    }
}
