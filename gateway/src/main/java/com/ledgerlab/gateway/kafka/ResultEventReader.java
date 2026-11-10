package com.ledgerlab.gateway.kafka;

import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.gateway.service.LedgerResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.EnumFeature;

/**
 * Tolerant reader of the ledger's result events (ADR 0011): a ledger one version ahead may send a reject
 * reason this gateway does not know yet. The outcome still applies, so the transfer is not left PUBLISHED;
 * the reason is kept as text, logged and counted.
 */
@Component
public class ResultEventReader {

    public static final String UNKNOWN_REASON_METRIC = "gateway.results.unknown-reason";
    public static final String LISTENER_TAG = "listener";

    private static final Logger log = LoggerFactory.getLogger(ResultEventReader.class);
    private static final String REJECT_REASON_FIELD = "rejectReason";

    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final ObjectReader reader;

    public enum Listener {
        RECORDER("recorder"),
        NOTIFIER("notifier");

        private final String tag;

        Listener(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    public ResultEventReader(ObjectMapper objectMapper, MeterRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.reader = objectMapper.readerFor(TransferResultEvent.class)
            .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
    }

    public LedgerResult read(String payload, Listener listener) {
        var tree = objectMapper.readTree(payload);
        TransferResultEvent event = reader.readValue(tree);
        var reasonNode = tree.get(REJECT_REASON_FIELD);
        var reason = reasonNode == null || reasonNode.isNull() ? null : reasonNode.asString();
        if (reason != null && event.rejectReason() == null) {
            registry.counter(UNKNOWN_REASON_METRIC, LISTENER_TAG, listener.tag()).increment();
            log.warn("Result of transfer {} has a reject reason this gateway does not know: {}", event.transferId(), reason);
        }
        return new LedgerResult(event.runId(), event.transferId(), event.outcome(), reason);
    }
}
