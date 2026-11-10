package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.gateway.api.GatewayErrors;
import com.ledgerlab.gateway.service.ResultWaiters;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "gateway.inflight.max-concurrent=1")
class InflightLimitTest extends IntegrationTest {

    @Autowired ResultWaiters waiters;
    @Autowired MeterRegistry registry;

    @Test
    @DisplayName("requests over the in-flight limit get 503 busy with Retry-After, waiting ones included")
    void requests_over_the_in_flight_limit_get_503_busy_with_Retry_After_waiting_ones_included() throws Exception {
        var refusedBefore = registry.counter("gateway.transfers", "outcome", "refused").count();
        var runId = UUID.randomUUID();
        var refusedId = UUID.randomUUID();

        try (var pool = Executors.newSingleThreadExecutor()) {
            var waiting = pool.submit(() -> submit(bodyOfRun(runId), "PT3S"));
            // Written and now waiting for a result that never comes.
            await().atMost(Duration.ofSeconds(10)).until(() -> waiters.size() > 0);

            var refused = submit(body(refusedId, runId));

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getBody().get("error")).isEqualTo(GatewayErrors.BUSY);
            assertThat(refused.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(registry.counter("gateway.transfers", "outcome", "refused").count()).isEqualTo(refusedBefore + 1);
            assertThat(get("/v1/transfers/" + refusedId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(waiting.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(submit(body(refusedId, runId)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
