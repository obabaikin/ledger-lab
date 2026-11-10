package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;

import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.service.LedgerResult;
import com.ledgerlab.gateway.service.ResultWaiters;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class WaitFailureTest extends IntegrationTest {

    @MockitoSpyBean TransferService service;
    @Autowired ResultWaiters waiters;
    @Autowired MeterRegistry registry;

    @Test
    @DisplayName("a database failure while reading the result answers 202, not 5xx, because the transfer is committed")
    void a_database_failure_while_reading_the_result_answers_202_not_5xx_because_the_transfer_is_committed() throws Exception {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var result = new LedgerResult(runId, transferId, TransferOutcome.POSTED, null);
        // Only the first read fails: the status query after the answer reads the row.
        doThrow(new DataAccessResourceFailureException("connection lost")).doCallRealMethod()
            .when(service).find(transferId);
        var failuresBefore = registry.counter("gateway.wait.failed").count();

        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> submit(body(transferId, runId), "PT5S"));
            await().atMost(Duration.ofSeconds(10)).until(() -> waiters.size() > 0);

            waiters.complete(result);
            var answer = pending.get();

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(answer.getBody().get("status")).isIn(TransferStatus.ACCEPTED.name(), TransferStatus.PUBLISHED.name());
            assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(registry.counter("gateway.wait.failed").count()).isEqualTo(failuresBefore + 1);
            assertThat(get("/v1/transfers/" + transferId).getStatusCode().is2xxSuccessful()).isTrue();
        }
    }
}
