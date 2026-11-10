package com.ledgerlab.reporting.kafka;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.reporting.persistence.AcceptedTransfer;
import com.ledgerlab.reporting.persistence.TransferOutcomeRow;
import com.ledgerlab.reporting.persistence.TransferStatsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.EnumFeature;

@Component
public class TransferEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsConsumer.class);
    private static final String REJECT_REASON_FIELD = "rejectReason";

    private final TransferStatsRepository repository;
    private final ObjectMapper objectMapper;
    private final ObjectReader commandReader;
    private final ObjectReader resultReader;

    public TransferEventsConsumer(TransferStatsRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.commandReader = objectMapper.readerFor(TransferCommand.class);
        this.resultReader = objectMapper.readerFor(TransferResultEvent.class)
            .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
    }

    @KafkaListener(topics = "${reporting.transfers-topic}", batch = "true")
    @Transactional
    public void onCommands(List<String> payloads) {
        var rows = new ArrayList<AcceptedTransfer>();
        for (var payload : payloads) {
            TransferCommand command = parsed(payload, () -> commandReader.readValue(payload));
            if (command != null) {
                rows.add(new AcceptedTransfer(command.transferId(), command.runId(), command.amountCents(), command.createdAt()));
            }
        }
        repository.upsertAccepted(rows);
    }

    @KafkaListener(topics = "${reporting.results-topic}", batch = "true")
    @Transactional
    public void onResults(List<String> payloads) {
        var rows = new ArrayList<TransferOutcomeRow>();
        for (var payload : payloads) {
            var row = parsed(payload, () -> readResult(payload));
            if (row != null) {
                rows.add(row);
            }
        }
        repository.upsertOutcomes(rows);
    }

    /** Tolerant reader (ADR 0011): a reject reason this service does not know yet is kept as text. */
    private TransferOutcomeRow readResult(String payload) {
        var tree = objectMapper.readTree(payload);
        TransferResultEvent event = resultReader.readValue(tree);
        var reasonNode = tree.get(REJECT_REASON_FIELD);
        var reason = reasonNode == null || reasonNode.isNull() ? null : reasonNode.asString();
        return new TransferOutcomeRow(event.transferId(), event.runId(), event.outcome().name(), reason, event.processedAt());
    }

    private <T> @Nullable T parsed(String payload, Supplier<T> read) {
        try {
            return read.get();
        } catch (JacksonException e) {
            log.error("Unreadable event skipped: {}", payload, e);
            return null;
        }
    }
}
