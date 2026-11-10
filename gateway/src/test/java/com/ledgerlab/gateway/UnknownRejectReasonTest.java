package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.kafka.ResultEventReader;
import com.ledgerlab.gateway.service.ResultWaiters;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** A ledger one version ahead may reject for a reason this gateway does not know yet (ADR 0011). */
class UnknownRejectReasonTest extends IntegrationTest {

    private static final String UNKNOWN_REASON = "CARD_EXPIRED";

    @Autowired ResultWaiters waiters;
    @Autowired MeterRegistry registry;

    private double unknownReasons(ResultEventReader.Listener listener) {
        return registry.counter(ResultEventReader.UNKNOWN_REASON_METRIC, ResultEventReader.LISTENER_TAG, listener.tag()).count();
    }

    private static String rejectedFor(UUID runId, UUID transferId, String reason) {
        return """
            {"runId":"%s","transferId":"%s","outcome":"REJECTED","rejectReason":"%s","processedAt":"%s"}"""
            .formatted(runId, transferId, reason, Instant.now());
    }

    @Test
    @DisplayName("an unknown reason still rejects the transfer, is kept as received and counted on both paths")
    void an_unknown_reason_still_rejects_the_transfer_is_kept_as_received_and_counted_on_both_paths() throws Exception {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var recordedBefore = unknownReasons(ResultEventReader.Listener.RECORDER);
        var notifiedBefore = unknownReasons(ResultEventReader.Listener.NOTIFIER);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var waiting = pool.submit(() -> submit(body(transferId, runId), "PT5S"));
            await().atMost(Duration.ofSeconds(10)).until(() -> waiters.size() > 0);

            publishRawResult(runId, transferId, rejectedFor(runId, transferId, UNKNOWN_REASON));

            var answer = waiting.get();
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody().get("status")).isEqualTo(TransferStatus.REJECTED.name());
            assertThat(answer.getBody().get("rejectReason")).isNull();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var stored = get("/v1/transfers/" + transferId).getBody();
            assertThat(stored.get("status")).isEqualTo(TransferStatus.REJECTED.name());
            assertThat(stored.get("rejectReason")).isNull();
            var summary = get("/internal/runs/" + runId + "/summary").getBody();
            assertThat(summary.get("rejectionsByReason")).isEqualTo(Map.of(UNKNOWN_REASON, 1));
            assertThat(unknownReasons(ResultEventReader.Listener.RECORDER)).isEqualTo(recordedBefore + 1);
            assertThat(unknownReasons(ResultEventReader.Listener.NOTIFIER)).isEqualTo(notifiedBefore + 1);
        });
    }
}
