package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.service.AcceptOutcome;
import com.ledgerlab.gateway.service.IdempotencyConflictException;
import com.ledgerlab.gateway.service.PeriodClosedException;
import com.ledgerlab.gateway.service.PeriodService;
import com.ledgerlab.gateway.service.TransferRequest;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class BatchAcceptTest extends IntegrationTest {

    private static final int ROUNDS = 10;
    private static final int KEYS = 200;

    @Autowired TransferService service;
    @Autowired PeriodService periods;
    @Autowired MeterRegistry registry;

    private static TransferRequest request(UUID runId) {
        return request(runId, UUID.randomUUID());
    }

    private static TransferRequest request(UUID runId, UUID transferId) {
        return new TransferRequest(transferId, runId, 1, 2, 100);
    }

    private static TransferRequest withAmount(TransferRequest request, long amountCents) {
        return new TransferRequest(request.transferId(), request.runId(), request.fromAccount(), request.toAccount(), amountCents);
    }

    private static Transfer accepted(AcceptOutcome outcome) {
        assertThat(outcome).isInstanceOf(AcceptOutcome.Accepted.class);
        return ((AcceptOutcome.Accepted) outcome).transfer();
    }

    private static RuntimeException refused(AcceptOutcome outcome) {
        assertThat(outcome).isInstanceOf(AcceptOutcome.Refused.class);
        return ((AcceptOutcome.Refused) outcome).cause();
    }

    private double counted(String outcome) {
        return registry.counter("gateway.transfers", "outcome", outcome).count();
    }

    @Test
    @DisplayName("each request in a batch gets the outcome it would get alone, in its own place")
    void each_request_in_a_batch_gets_the_outcome_it_would_get_alone_in_its_own_place() {
        var run = UUID.randomUUID();
        var closedRun = UUID.randomUUID();
        var stored = request(run);
        var storedInClosed = request(closedRun);
        service.acceptAll(List.of(stored, storedInClosed));
        periods.close(closedRun);
        var fresh = request(run);
        var sameKeyTwice = request(run);
        var sameKeyOtherAmount = withAmount(sameKeyTwice, 999);
        var acceptedBefore = counted("accepted");
        var repeatedBefore = counted("repeated");

        var outcomes = service.acceptAll(
            List.of(
                fresh,
                stored,
                withAmount(stored, 1),
                sameKeyTwice,
                sameKeyTwice,
                sameKeyOtherAmount,
                storedInClosed,
                request(closedRun)));

        assertThat(accepted(outcomes.get(0)).transferId()).isEqualTo(fresh.transferId());
        assertThat(accepted(outcomes.get(1)).transferId()).isEqualTo(stored.transferId());
        assertThat(refused(outcomes.get(2))).isInstanceOf(IdempotencyConflictException.class);
        assertThat(accepted(outcomes.get(3)).amountCents()).isEqualTo(100);
        assertThat(accepted(outcomes.get(4)).transferId()).isEqualTo(sameKeyTwice.transferId());
        assertThat(refused(outcomes.get(5))).isInstanceOf(IdempotencyConflictException.class);
        assertThat(accepted(outcomes.get(6)).transferId()).isEqualTo(storedInClosed.transferId());
        assertThat(refused(outcomes.get(7))).isInstanceOf(PeriodClosedException.class);
        assertThat(counted("accepted")).isEqualTo(acceptedBefore + 2);
        assertThat(counted("repeated")).isEqualTo(repeatedBefore + 3);
    }

    @Test
    @DisplayName("a request of a closed run gets 422, not the row another run's request inserted under its key in the same batch")
    void a_request_of_a_closed_run_gets_422_not_the_row_another_runs_request_inserted_under_its_key_in_the_same_batch() {
        var openRun = UUID.randomUUID();
        var closedRun = UUID.randomUUID();
        service.acceptAll(List.of(request(closedRun)));
        periods.close(closedRun);
        var key = UUID.randomUUID();

        var outcomes = service.acceptAll(List.of(request(closedRun, key), request(openRun, key)));

        assertThat(refused(outcomes.get(0))).isInstanceOf(IdempotencyConflictException.class);
        assertThat(accepted(outcomes.get(1)).runId()).isEqualTo(openRun);
    }

    @Test
    void two_batches_with_the_same_keys_in_opposite_orders_both_commit_without_a_deadlock() throws Exception {
        for (var round = 0; round < ROUNDS; round++) {
            var run = UUID.randomUUID();
            var batch = Stream.generate(() -> request(run)).limit(KEYS).toList();
            var start = new CountDownLatch(1);
            var outcomes = new ArrayList<AcceptOutcome>();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var writing = new ArrayList<Future<List<AcceptOutcome>>>();
                for (var requests : List.of(batch, batch.reversed())) {
                    writing.add(pool.submit(() -> {
                        start.await();
                        return service.acceptAll(requests);
                    }));
                }
                start.countDown();
                for (var future : writing) {
                    outcomes.addAll(future.get());
                }
            }

            assertThat(outcomes).allMatch(AcceptOutcome.Accepted.class::isInstance);
            assertThat(service.summarize(run).accepted()).isEqualTo(KEYS);
        }
    }

    @Test
    @DisplayName("concurrent submissions through the queue each get their own answer, and a repeat with another body is refused")
    void concurrent_submissions_through_the_queue_each_get_their_own_answer_and_a_repeat_with_another_body_is_refused() throws Exception {
        var run = UUID.randomUUID();
        var ids = Stream.generate(UUID::randomUUID).limit(200).toList();

        // No more clients than admission permits: a slow machine must not turn this into a test of 503 busy.
        var statuses = new ArrayList<HttpStatusCode>();
        try (var pool = Executors.newFixedThreadPool(props.admission().maxConcurrent())) {
            var submitting = new ArrayList<Future<HttpStatusCode>>();
            var twice = new ArrayList<>(ids);
            twice.addAll(ids);
            for (var id : twice) {
                submitting.add(pool.submit(() -> submit(body(id, run)).getStatusCode()));
            }
            for (var future : submitting) {
                statuses.add(future.get());
            }
        }

        assertThat(statuses).containsOnly(HttpStatus.ACCEPTED);
        assertThat(service.summarize(run).accepted()).isEqualTo(ids.size());
        assertThat(submit(body(ids.getFirst(), run, 1, 2, 1)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
