package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ledgerlab.gateway.api.SubmitTransferRequest;
import com.ledgerlab.gateway.api.TransferController;
import com.ledgerlab.gateway.api.TransferResponse;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.service.RecordedResultReader;
import com.ledgerlab.gateway.service.ResultWaiters;
import com.ledgerlab.gateway.service.TransferAdmission;
import com.ledgerlab.gateway.service.TransferBatcher;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InterruptedWaitTest {

    private static final Duration LONG_WAIT = Duration.ofSeconds(30);

    @Test
    @DisplayName("a wait for the result that is interrupted answers with the accepted transfer and leaves the thread interrupted")
    void a_wait_for_the_result_that_is_interrupted_answers_with_the_accepted_transfer_and_leaves_the_thread_interrupted() throws Exception {
        var transferId = UUID.randomUUID();
        var accepted = new Transfer(transferId, 1, 2, 100, TransferStatus.ACCEPTED, null, Instant.now(), null);
        var batcher = mock(TransferBatcher.class);
        when(batcher.accept(any())).thenReturn(accepted);
        var waiters = new ResultWaiters();
        var registry = new SimpleMeterRegistry();
        var props = TransferAdmissionTest.props(new GatewayProperties.Accept(1, 1, Duration.ofSeconds(5)));
        var controller = new TransferController(
            mock(TransferService.class), batcher, waiters, mock(RecordedResultReader.class), new TransferAdmission(props, registry), props, registry);
        var answer = new AtomicReference<ResponseEntity<TransferResponse>>();
        var leftInterrupted = new AtomicBoolean();
        var request = Thread.ofPlatform().start(() -> {
            try {
                answer.set(controller.submit(new SubmitTransferRequest(transferId, 1, 2, 100), LONG_WAIT));
            } catch (InterruptedException e) {
                throw new AssertionError("the accepted transfer must be answered", e);
            }
            leftInterrupted.set(Thread.currentThread().isInterrupted());
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> waiters.size() == 1);

        request.interrupt();
        request.join(Duration.ofSeconds(5));

        assertThat(answer.get()).isNotNull();
        assertThat(answer.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(leftInterrupted).isTrue();
        assertThat(registry.counter("gateway.wait.failed").count()).isZero();
        assertThat(waiters.size()).isZero();
    }
}
