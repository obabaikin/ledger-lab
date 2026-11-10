package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.service.LedgerResult;
import com.ledgerlab.gateway.service.ResultWaiters;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class ResultHandlingTest extends IntegrationTest {

    @Autowired ResultWaiters waiters;
    @Autowired MeterRegistry registry;
    @Autowired TransferService service;

    @Test
    void a_result_from_the_ledger_becomes_the_transfer_status() throws Exception {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        submit(body(transferId, runId));

        publishResult(runId, transferId, TransferOutcome.REJECTED, RejectReason.INSUFFICIENT_FUNDS);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var response = get("/v1/transfers/" + transferId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("status")).isEqualTo(TransferStatus.REJECTED.name());
            assertThat(response.getBody().get("rejectReason")).isEqualTo(RejectReason.INSUFFICIENT_FUNDS.name());
        });
    }

    @Test
    void a_waiting_request_is_answered_with_the_status_the_recorder_committed() throws Exception {
        var readsBefore = registry.summary("gateway.wait.reads").count();
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> submit(body(transferId, runId), "PT5S"));
            await().atMost(Duration.ofSeconds(10)).until(() -> waiters.size() > 0);

            publishResult(runId, transferId, TransferOutcome.POSTED);

            var response = pending.get();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("status")).isEqualTo(TransferStatus.POSTED.name());
            assertThat(waiters.size()).isZero();
            assertThat(registry.summary("gateway.wait.reads").count()).isGreaterThan(readsBefore);
        }
    }

    @Test
    @DisplayName("a woken request only reads, so without the recorder's write it answers 202 after its last read and the row stays as it was")
    void a_woken_request_only_reads_so_without_the_recorders_write_it_answers_202_after_its_last_read_and_the_row_stays_as_it_was()
        throws Exception {
        var readsBefore = registry.summary("gateway.wait.reads").totalAmount();
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> submit(body(transferId, runId), "PT5S"));
            await().atMost(Duration.ofSeconds(10)).until(() -> waiters.size() > 0);

            // Wake the request directly, bypassing Kafka, so the recorder never writes this result.
            waiters.complete(new LedgerResult(runId, transferId, TransferOutcome.POSTED, null));

            var answer = pending.get();
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            var stored = get("/v1/transfers/" + transferId);
            assertThat(stored.getBody().get("status")).isIn(TransferStatus.ACCEPTED.name(), TransferStatus.PUBLISHED.name());
            assertThat(stored.getBody().get("resultedAt")).isNull();
            assertThat(registry.summary("gateway.wait.reads").totalAmount() - readsBefore).isEqualTo(props.waitRead().maxReads());
        }
    }

    @Test
    void two_requests_waiting_for_the_same_transfer_are_both_answered() throws Exception {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> submit(body(transferId, runId), "PT0.5S"));
            var second = pool.submit(() -> submit(body(transferId, runId), "PT5S"));
            await().atMost(Duration.ofSeconds(10)).until(() -> waiters.size() == 2);

            assertThat(first.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); // the short wait times out first
            await().atMost(Duration.ofSeconds(5)).until(() -> waiters.size() == 1);

            publishResult(runId, transferId, TransferOutcome.POSTED);

            var response = second.get();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("status")).isEqualTo(TransferStatus.POSTED.name());
            assertThat(waiters.size()).isZero();
        }
    }

    @Test
    void a_waiting_request_times_out_with_202_and_the_current_state() {
        var response = submit(body(), "PT0.3S");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status")).isIn(TransferStatus.ACCEPTED.name(), TransferStatus.PUBLISHED.name());
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(waiters.size()).isZero();
    }

    @Test
    void a_repeated_submission_after_the_result_returns_the_final_state_immediately() throws Exception {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var request = body(transferId, runId);
        submit(request);
        publishResult(runId, transferId, TransferOutcome.POSTED);
        await().atMost(Duration.ofSeconds(30)).until(() ->
            TransferStatus.POSTED.name().equals(get("/v1/transfers/" + transferId).getBody().get("status")));

        var again = submit(request, "PT5S");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("status")).isEqualTo(TransferStatus.POSTED.name());
    }

    @Test
    void a_result_that_arrives_before_the_outbox_mark_does_not_get_downgraded() throws Exception {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        submit(body(transferId, runId));

        publishResult(runId, transferId, TransferOutcome.POSTED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(get("/v1/transfers/" + transferId).getBody().get("status")).isEqualTo(TransferStatus.POSTED.name()));
        Thread.sleep(300); // give the outbox publisher time to run after the result
        assertThat(get("/v1/transfers/" + transferId).getBody().get("status")).isEqualTo(TransferStatus.POSTED.name());
    }

    @Test
    void a_poll_of_results_is_recorded_in_one_update_and_a_final_status_is_never_overwritten() {
        var runId = UUID.randomUUID();
        var posted = UUID.randomUUID();
        var rejected = UUID.randomUUID();
        var unknown = UUID.randomUUID();
        submit(body(posted, runId));
        submit(body(rejected, runId));

        service.recordResults(
            List.of(
                new LedgerResult(runId, posted, TransferOutcome.POSTED, null),
                new LedgerResult(runId, rejected, TransferOutcome.REJECTED, RejectReason.BLOCKED_ACCOUNT.name()),
                new LedgerResult(runId, unknown, TransferOutcome.POSTED, null)));
        service.recordResults(List.of(new LedgerResult(runId, posted, TransferOutcome.REJECTED, RejectReason.INSUFFICIENT_FUNDS.name())));

        assertThat(service.find(posted).status()).isEqualTo(TransferStatus.POSTED);
        assertThat(service.find(posted).rejectReason()).isNull();
        assertThat(service.find(rejected).status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(service.find(rejected).rejectReason()).isEqualTo(RejectReason.BLOCKED_ACCOUNT);
        assertThat(service.find(unknown)).isNull();
    }
}
