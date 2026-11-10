package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.service.AcceptOutcome;
import com.ledgerlab.gateway.service.AcceptTimeoutException;
import com.ledgerlab.gateway.service.GatewayStoppingException;
import com.ledgerlab.gateway.service.TransferBatcher;
import com.ledgerlab.gateway.service.TransferRequest;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

class TransferBatcherTest {

    private static final long GATHER_MILLIS = 200L;

    private final TransferService service = mock(TransferService.class);
    private final List<Integer> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstHeld = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private TransferBatcher batcher;

    @AfterEach
    void tearDown() throws InterruptedException {
        releaseFirst.countDown();
        batcher.stop();
        pool.shutdownNow();
    }

    private TransferBatcher batcher() {
        return batcher(Duration.ofSeconds(5));
    }

    private TransferBatcher batcher(Duration answerTimeout) {
        var props = TransferAdmissionTest.props(new GatewayProperties.Accept(10, 1, answerTimeout));
        batcher = new TransferBatcher(service, props, new SimpleMeterRegistry());
        batcher.start();
        return batcher;
    }

    private static TransferRequest request() {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100);
    }

    private static List<AcceptOutcome> allAccepted(List<TransferRequest> requests) {
        return requests.stream()
            .<AcceptOutcome>map(it -> new AcceptOutcome.Accepted(new Transfer(
                it.transferId(), it.runId(), it.fromAccount(), it.toAccount(), it.amountCents(), TransferStatus.ACCEPTED, null, Instant.now(), null)))
            .toList();
    }

    /** The first call holds the only writer, so the next requests gather into one batch behind it. */
    private void answering(Function<List<TransferRequest>, List<AcceptOutcome>> batch) {
        doAnswer(call -> {
            List<TransferRequest> requests = call.getArgument(0);
            calls.add(requests.size());
            if (calls.size() == 1) {
                firstHeld.countDown();
                releaseFirst.await();
            }
            return batch.apply(requests);
        }).when(service).acceptAll(anyList());
    }

    private List<Future<Transfer>> submitBehindFirst(int count) throws Exception {
        var first = pool.submit(() -> batcher.accept(request()));
        firstHeld.await(5, TimeUnit.SECONDS);
        var rest = new ArrayList<Future<Transfer>>();
        for (var i = 0; i < count; i++) {
            rest.add(pool.submit(() -> batcher.accept(request())));
        }
        Thread.sleep(GATHER_MILLIS);
        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        return rest;
    }

    @Test
    void a_batch_that_gets_no_connection_fails_as_a_whole_and_is_not_split_into_single_writes() throws Exception {
        answering(requests -> {
            if (requests.size() > 1) {
                throw new CannotCreateTransactionException("no connection");
            }
            return allAccepted(requests);
        });
        batcher();

        var gathered = submitBehindFirst(3);

        for (var future : gathered) {
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CannotCreateTransactionException.class);
        }
        assertThat(calls).containsExactly(1, 3);
    }

    @Test
    void a_batch_whose_data_the_database_refuses_is_written_again_one_by_one() throws Exception {
        answering(requests -> {
            if (requests.size() > 1) {
                throw new DataIntegrityViolationException("bad row");
            }
            return allAccepted(requests);
        });
        batcher();

        var gathered = submitBehindFirst(3);

        for (var future : gathered) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(calls).containsExactly(1, 3, 1, 1, 1);
    }

    @Test
    void a_writer_outlives_an_error_of_its_batch_and_answers_the_next_one() throws Exception {
        answering(requests -> {
            if (requests.size() > 1) {
                throw new OutOfMemoryError("simulated");
            }
            return allAccepted(requests);
        });
        batcher();

        var gathered = submitBehindFirst(2);
        for (var future : gathered) {
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(OutOfMemoryError.class);
        }

        assertThat(batcher.accept(request())).isNotNull();
    }

    @Test
    @DisplayName("a request the writers do not answer in time gets a timeout, not an endless wait")
    void a_request_the_writers_do_not_answer_in_time_gets_a_timeout_not_an_endless_wait() throws Exception {
        answering(TransferBatcherTest::allAccepted);
        batcher(Duration.ofMillis(200));

        var held = pool.submit(() -> batcher.accept(request()));
        firstHeld.await(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> batcher.accept(request())).isInstanceOf(AcceptTimeoutException.class);
        held.cancel(true);
    }

    @Test
    void requests_still_in_the_queue_when_the_gateway_stops_are_answered_that_it_is_stopping() throws Exception {
        answering(TransferBatcherTest::allAccepted);
        batcher();
        var held = pool.submit(() -> batcher.accept(request()));
        firstHeld.await(5, TimeUnit.SECONDS);
        var queued = new ArrayList<Future<Transfer>>();
        for (var i = 0; i < 3; i++) {
            queued.add(pool.submit(() -> batcher.accept(request())));
        }
        Thread.sleep(GATHER_MILLIS);

        batcher.stop();

        for (var future : queued) {
            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(GatewayStoppingException.class);
        }
        held.cancel(true);
    }

    @Test
    void a_request_arriving_after_the_stop_is_refused_at_once() throws Exception {
        answering(TransferBatcherTest::allAccepted);
        batcher().stop();

        assertThatThrownBy(() -> batcher.accept(request())).isInstanceOf(GatewayStoppingException.class);
    }
}
