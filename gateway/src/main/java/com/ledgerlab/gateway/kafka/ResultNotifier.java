package com.ledgerlab.gateway.kafka;

import com.ledgerlab.gateway.service.ResultWaiters;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Notification path: every instance sees every result so that the instance holding an open
 * request can answer it. Per-instance anonymous group that never commits offsets: nothing to
 * replay after a restart, and the broker drops the empty group instead of keeping it.
 */
@Component
public class ResultNotifier {

    private final ResultWaiters waiters;
    private final ResultEventReader reader;

    public ResultNotifier(ResultWaiters waiters, ResultEventReader reader) {
        this.waiters = waiters;
        this.reader = reader;
    }

    @KafkaListener(
        topics = "${gateway.results-topic}",
        groupId = "gateway-notify-#{T(java.util.UUID).randomUUID()}",
        containerFactory = KafkaConfig.NOTIFIER_CONTAINER_FACTORY,
        properties = "auto.offset.reset=latest")
    public void onResult(String payload) {
        waiters.complete(reader.read(payload, ResultEventReader.Listener.NOTIFIER));
    }
}
