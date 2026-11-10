package com.ledgerlab.gateway.kafka;

import static com.ledgerlab.common.Observability.MDC_RUN_ID;

import com.ledgerlab.gateway.service.LedgerResult;
import com.ledgerlab.gateway.service.TransferService;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/** Durable path: exactly one gateway instance records each result in the database, one transaction per poll. */
@Component
public class ResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(ResultRecorder.class);

    private final TransferService service;
    private final ResultEventReader reader;

    public ResultRecorder(TransferService service, ResultEventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    @KafkaListener(topics = "${gateway.results-topic}", groupId = "${spring.kafka.consumer.group-id}", batch = "true")
    public void onResults(List<String> payloads) {
        var results = new ArrayList<LedgerResult>(payloads.size());
        for (var payload : payloads) {
            var result = parse(payload);
            if (result != null) {
                results.add(result);
            }
        }
        var runIds = results.stream().map(LedgerResult::runId).distinct().toList();
        if (runIds.size() == 1) {
            MDC.put(MDC_RUN_ID, runIds.getFirst().toString());
        }
        try {
            service.recordResults(results);
        } finally {
            MDC.remove(MDC_RUN_ID);
        }
    }

    // In a batch an exception would fail the whole poll; a message that can never be read is skipped on its own.
    private @Nullable LedgerResult parse(String payload) {
        try {
            return reader.read(payload, ResultEventReader.Listener.RECORDER);
        } catch (JacksonException e) {
            log.error("Unreadable result skipped: {}", payload, e);
            return null;
        }
    }
}
