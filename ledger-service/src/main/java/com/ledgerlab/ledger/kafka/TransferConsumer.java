package com.ledgerlab.ledger.kafka;

import static com.ledgerlab.common.Observability.MDC_RUN_ID;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.TransferResult;
import com.ledgerlab.ledger.metrics.RunDuplicates;
import com.ledgerlab.ledger.service.LedgerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public class TransferConsumer {

    public static final String LISTENER_ID = "transfers";

    private static final Logger log = LoggerFactory.getLogger(TransferConsumer.class);

    private final LedgerService ledger;
    private final RunDuplicates duplicates;
    private final ObjectReader reader;
    private final Counter posted;
    private final Counter rejected;

    public TransferConsumer(LedgerService ledger, ObjectMapper objectMapper, RunDuplicates duplicates, MeterRegistry registry) {
        this.ledger = ledger;
        this.duplicates = duplicates;
        this.reader = objectMapper.readerFor(TransferCommand.class);
        this.posted = outcomeCounter(registry, "posted");
        this.rejected = outcomeCounter(registry, "rejected");
    }

    // The id lets the readiness check find this container; without idIsGroup = false it would also replace the group id.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "${ledger.topic.name}", batch = "true")
    public void onTransfers(List<String> payloads) {
        var commands = new ArrayList<TransferCommand>(payloads.size());
        for (var payload : payloads) {
            var command = parse(payload);
            if (command != null) {
                commands.add(command);
            }
        }
        var runIds = commands.stream().map(TransferCommand::runId).distinct().toList();
        if (runIds.size() == 1) {
            MDC.put(MDC_RUN_ID, runIds.getFirst().toString());
        }
        try {
            var results = ledger.applyBatch(commands);
            for (var i = 0; i < commands.size(); i++) {
                count(commands.get(i), results.get(i));
            }
        } finally {
            MDC.remove(MDC_RUN_ID);
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
            case TransferResult.AlreadyProcessed() -> {
                duplicates.record(command.runId());
                log.debug("Duplicate delivery ignored: run={} transfer={}", command.runId(), command.transferId());
            }
        }
    }

    private static Counter outcomeCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("ledger.transfers").tag("outcome", outcome).register(registry);
    }
}
