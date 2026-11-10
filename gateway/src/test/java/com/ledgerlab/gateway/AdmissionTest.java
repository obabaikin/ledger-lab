package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import com.ledgerlab.gateway.api.GatewayErrors;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@TestPropertySource(properties = {"gateway.admission.max-concurrent=1", "gateway.admission.wait=50ms"})
class AdmissionTest extends IntegrationTest {

    @MockitoSpyBean TransferService service;
    @Autowired MeterRegistry registry;

    @Test
    @DisplayName("a submission over the limit is refused with 503 busy before it is written, and a retry is taken")
    void a_submission_over_the_limit_is_refused_with_503_busy_before_it_is_written_and_a_retry_is_taken() throws Exception {
        var held = new CountDownLatch(1);
        var inside = new AtomicInteger();
        doAnswer(call -> {
            inside.incrementAndGet();
            held.await();
            return call.callRealMethod();
        }).when(service).acceptAll(anyList());
        var refusedBefore = registry.counter("gateway.transfers", "outcome", "refused").count();
        var runId = UUID.randomUUID();
        var refusedId = UUID.randomUUID();

        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> submit(bodyOfRun(runId)));
            await().atMost(Duration.ofSeconds(10)).until(() -> inside.get() == 1);

            var refused = submit(body(refusedId, runId));

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getBody().get("error")).isEqualTo(GatewayErrors.BUSY);
            assertThat(refused.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(registry.counter("gateway.transfers", "outcome", "refused").count()).isEqualTo(refusedBefore + 1);
            assertThat(get("/v1/transfers/" + refusedId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            held.countDown();
            assertThat(first.get().getStatusCode().is2xxSuccessful()).isTrue();
        }
        assertThat(submit(body(refusedId, runId)).getStatusCode().is2xxSuccessful()).isTrue();
    }
}
