package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class GatewayClientTest {

    // As many as the server's slow answers kept open at 1300 TPS, where the generator's container ran out of memory.
    private static final int OPEN_SUBMISSIONS = 500;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private GatewayClient client(String url, int maxAttempts) {
        return client(url, maxAttempts, Duration.ofMillis(50));
    }

    private GatewayClient client(String url, int maxAttempts, Duration retryBackoff) {
        return TestData.settings(url).maxAttempts(maxAttempts).retryBackoff(retryBackoff).client(registry);
    }

    private double unreadable() {
        return registry.counter(GatewayClient.UNREADABLE_METRIC).count();
    }

    /** The kinds of the sends of one submission, in the order they ended. */
    private static SendRecorder into(List<ResponseKind> kinds) {
        return (attempt, startedNanos, endedNanos, kind, finalStatus) -> kinds.add(kind);
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int httpClientPlatformWorkers() {
        return (int) Thread.getAllStackTraces().keySet().stream()
            .filter(it -> !it.isVirtual() && it.getName().startsWith("HttpClient-") && it.getName().contains("-Worker-"))
            .count();
    }

    @Test
    void answers_to_many_open_submissions_arriving_at_once_take_no_platform_thread_each() throws Exception {
        try (var gateway = new FakeGateway(); var submitting = Executors.newVirtualThreadPerTaskExecutor()) {
            var held = new CountDownLatch(1);
            gateway.answersHeld = held;
            var client = client(gateway.url(), 1);
            var pending = new ArrayList<Future<SubmissionResult>>();
            for (var i = 0; i < OPEN_SUBMISSIONS; i++) {
                pending.add(submitting.submit(() -> client.submit(TestData.transfer(), null)));
            }
            await().atMost(Duration.ofSeconds(30)).until(() -> gateway.requests.get() == OPEN_SUBMISSIONS);

            // Other clients in this JVM keep idle workers of the same name for a minute; only new ones count.
            var before = httpClientPlatformWorkers();
            var peak = new AtomicInteger(before);
            var watcher = Thread.ofPlatform().start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    peak.accumulateAndGet(httpClientPlatformWorkers(), Math::max);
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            held.countDown();
            var results = new ArrayList<SubmissionResult>();
            for (var future : pending) {
                results.add(future.get());
            }
            watcher.interrupt();
            watcher.join();

            assertThat(results).allSatisfy(it -> assertThat(it.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED));
            assertThat(peak.get() - before).isZero();
        }
    }

    @Test
    void a_gateway_that_cannot_be_reached_on_any_attempt_is_a_failure() throws Exception {
        var kinds = new CopyOnWriteArrayList<ResponseKind>();

        var result = client("http://localhost:" + closedPort(), 2).submit(TestData.transfer(), null, () -> true, into(kinds));

        assertThat(result.outcome()).isEqualTo(SubmissionOutcome.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(kinds).containsExactly(ResponseKind.NOT_CONNECTED, ResponseKind.NOT_CONNECTED);
    }

    @Test
    @DisplayName("once an attempt may have reached the gateway, a later connection failure keeps it unknown")
    void once_an_attempt_may_have_reached_the_gateway_a_later_connection_failure_keeps_it_unknown() throws Exception {
        var gateway = FakeGateway.with().failFirstAttempt().start();
        var client = client(gateway.url(), 2, Duration.ofSeconds(1));
        var kinds = new CopyOnWriteArrayList<ResponseKind>();

        try (var submitting = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = submitting.submit(() -> client.submit(TestData.transfer(), null, () -> true, into(kinds)));
            // Waiting for the request alone is not enough: closing before the 503 is written makes the first send NO_RESPONSE.
            await().atMost(Duration.ofSeconds(5)).until(() -> !kinds.isEmpty());
            gateway.close(); // the retry finds no gateway
            var result = pending.get();

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.UNKNOWN);
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(kinds).containsExactly(ResponseKind.SERVER_ERROR, ResponseKind.NOT_CONNECTED);
        }
    }

    @Test
    @DisplayName("a 503 busy and a 503 stopping are both retried, and neither leaves the transfer unknown")
    void a_503_busy_and_a_503_stopping_are_both_retried_and_neither_leaves_the_transfer_unknown() throws Exception {
        try (var gateway = FakeGateway.with().busyAttempts(1).stoppingAttempts(2).start()) {
            var kinds = new CopyOnWriteArrayList<ResponseKind>();

            var taken = client(gateway.url(), 3).submit(TestData.transfer(), null, () -> true, into(kinds));
            var givenUp = client(gateway.url(), 2).submit(TestData.transfer(), null);

            assertThat(kinds).containsExactly(ResponseKind.BUSY, ResponseKind.STOPPING, ResponseKind.ACCEPTED);
            assertThat(taken.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED);
            assertThat(taken.refusals()).isEqualTo(1);
            assertThat(givenUp.outcome()).isEqualTo(SubmissionOutcome.FAILED);
        }
    }

    @Test
    @DisplayName("after a 503 busy the retry waits for Retry-After")
    void after_a_503_busy_the_retry_waits_for_Retry_After() throws Exception {
        try (var gateway = FakeGateway.with().busyAttempts(1).retryAfterSeconds(1).start()) {
            var result = client(gateway.url(), 2).submit(TestData.transfer(), null);

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED);
            assertThat(result.latency()).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        }
    }

    @Test
    @DisplayName("an error of the client itself while sending ends the submission as unknown, without a retry")
    void an_error_of_the_client_itself_while_sending_ends_the_submission_as_unknown_without_a_retry() throws Exception {
        var sends = new AtomicInteger();
        var failing = new FailingHttpClient(() -> {
            sends.incrementAndGet();
            return new IOException("wrapped by the HTTP client", new IllegalStateException("not an I/O failure"));
        });
        var kinds = new CopyOnWriteArrayList<ResponseKind>();
        var client = new GatewayClient(TestData.settings("http://localhost:1").maxAttempts(3).build(), new ObjectMapper(), registry, failing);

        var result = client.submit(TestData.transfer(), null, () -> true, into(kinds));

        assertThat(result.outcome()).isEqualTo(SubmissionOutcome.UNKNOWN);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(sends.get()).isEqualTo(1);
        assertThat(kinds).containsExactly(ResponseKind.SEND_ERROR);
    }

    @Test
    @DisplayName("an I/O failure after the request may have left is retried")
    void an_io_failure_after_the_request_may_have_left_is_retried() throws Exception {
        var sends = new AtomicInteger();
        var failing = new FailingHttpClient(() -> {
            sends.incrementAndGet();
            return new IOException("wrapped by the HTTP client", new IOException("connection reset"));
        });
        var client = new GatewayClient(TestData.settings("http://localhost:1").maxAttempts(3).build(), new ObjectMapper(), registry, failing);

        var result = client.submit(TestData.transfer(), null);

        assertThat(result.outcome()).isEqualTo(SubmissionOutcome.UNKNOWN);
        assertThat(sends.get()).isEqualTo(3);
    }

    @Test
    void a_2xx_with_a_status_this_client_does_not_know_is_acknowledged_without_a_status() throws Exception {
        try (var gateway = FakeGateway.with().rawAnswer(it -> """
            {"transferId":"%s","status":"SETTLED","acceptedAt":"%s"}""".formatted(it.transferId(), Instant.now())).start()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED);
            assertThat(result.status()).isNull();
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    void a_2xx_without_a_field_this_client_needs_is_acknowledged_by_its_code_and_counted_as_unreadable() throws Exception {
        try (var gateway = FakeGateway.with().rawAnswer(it -> """
            {"status":"ACCEPTED","acceptedAt":"%s"}""".formatted(Instant.now())).start()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED);
            assertThat(result.status()).isNull();
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    void a_reject_reason_this_client_does_not_know_keeps_the_status_it_knows() throws Exception {
        try (var gateway = FakeGateway.with().rawAnswer(it -> """
            {"transferId":"%s","status":"%s","rejectReason":"CARD_EXPIRED","acceptedAt":"%s"}"""
            .formatted(it.transferId(), TransferStatus.REJECTED, Instant.now())).start()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED);
            assertThat(result.status()).isEqualTo(TransferStatus.REJECTED);
            assertThat(result.rejectReason()).isNull();
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    void an_answer_it_can_read_and_a_refusal_with_an_error_body_are_not_counted_as_unreadable() throws Exception {
        try (var gateway = new FakeGateway()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);
            assertThat(result.acceptedAt()).isNotNull();
        }
        try (var gateway = FakeGateway.with().refuseWith(HttpStatus.CONFLICT.value()).start()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);
            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.FAILED);
        }

        assertThat(unreadable()).isZero();
    }

    private static final Instant SOME_PERIOD = Instant.parse("2026-11-11T10:00:00Z");

    private static String statementPage(String status, String rejectReason) {
        return """
            {"entryCount":1,"items":[{"transferId":"%s","fromAccount":1,"toAccount":2,"amountCents":100,"status":"%s","rejectReason":%s}],"next":null}"""
            .formatted(UUID.randomUUID(), status, rejectReason == null ? "null" : "\"" + rejectReason + "\"");
    }

    @Test
    void a_statement_line_with_a_status_this_client_does_not_know_is_read_without_it_and_counted() {
        try (var gateway = FakeGateway.with().rawStatement(statementPage("SETTLED", null)).start()) {
            var page = client(gateway.url(), 1).statement(SOME_PERIOD, null);

            assertThat(page.items()).singleElement().satisfies(line -> assertThat(line.status()).isNull());
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    void a_statement_line_with_a_reject_reason_this_client_does_not_know_keeps_its_status_and_is_counted() {
        try (var gateway = FakeGateway.with().rawStatement(statementPage(TransferStatus.REJECTED.name(), "CARD_EXPIRED")).start()) {
            var page = client(gateway.url(), 1).statement(SOME_PERIOD, null);

            assertThat(page.items()).singleElement().satisfies(line -> {
                assertThat(line.status()).isEqualTo(TransferStatus.REJECTED);
                assertThat(line.rejectReason()).isNull();
            });
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("a statement page without an entry count, as from a gateway older than this client, is a bad answer")
    void a_statement_page_without_an_entry_count_as_from_a_gateway_older_than_this_client_is_a_bad_answer() {
        try (var gateway = FakeGateway.with().rawStatement("{\"items\":[],\"next\":null}").start()) {
            assertThatThrownBy(() -> client(gateway.url(), 1).statement(SOME_PERIOD, null))
                .isInstanceOf(BadGatewayAnswerException.class);
        }
    }

    @Test
    void a_statement_page_it_can_read_in_full_is_not_counted_as_unreadable() throws Exception {
        try (var gateway = FakeGateway.with().rejectReason(RejectReason.BLOCKED_ACCOUNT).start()) {
            var client = client(gateway.url(), 1);
            var result = client.submit(TestData.transfer(), Duration.ofSeconds(1));
            var period = Period.containing(result.acceptedAt(), Duration.ofHours(1));

            assertThat(client.statement(period.start(), null).items()).singleElement()
                .satisfies(line -> assertThat(line.rejectReason()).isEqualTo(RejectReason.BLOCKED_ACCOUNT));
            assertThat(unreadable()).isZero();
        }
    }

    @Test
    @DisplayName("a statement that is not closed, a gateway that fails and one that answers nonsense are told apart")
    void a_statement_that_is_not_closed_a_gateway_that_fails_and_one_that_answers_nonsense_are_told_apart() {
        try (var open = FakeGateway.with().periodNotClosed().start();
             var failing = FakeGateway.with().statementFailure(request -> HttpStatus.INTERNAL_SERVER_ERROR.value()).start();
             var wrong = FakeGateway.with().statementFailure(request -> HttpStatus.BAD_REQUEST.value()).start()) {
            assertThatThrownBy(() -> client(open.url(), 1).statement(SOME_PERIOD, null)).isInstanceOf(PeriodNotClosedException.class);
            assertThatThrownBy(() -> client(failing.url(), 1).statement(SOME_PERIOD, null)).isInstanceOf(GatewayUnavailableException.class);
            assertThatThrownBy(() -> client(wrong.url(), 1).statement(SOME_PERIOD, null)).isInstanceOf(BadGatewayAnswerException.class);
        }
    }

    @Test
    @DisplayName("asked about a transfer, the bank's answer is the transfer, or nothing for one it does not have")
    void asked_about_a_transfer_the_banks_answer_is_the_transfer_or_nothing_for_one_it_does_not_have() throws Exception {
        try (var gateway = new FakeGateway()) {
            var client = client(gateway.url(), 1);
            var transfer = TestData.transfer();
            var submitted = client.submit(transfer, null);

            var known = client.transfer(transfer.transferId());

            assertThat(known).isNotNull();
            assertThat(known.status()).isEqualTo(TransferStatus.POSTED);
            assertThat(known.acceptedAt()).isEqualTo(submitted.acceptedAt());
            assertThat(client.transfer(UUID.randomUUID())).isNull();
        }
    }

    @Test
    void a_gateway_that_fails_when_asked_about_a_transfer_is_not_taken_for_a_bank_without_it() {
        try (var gateway = FakeGateway.with().transferFailure(HttpStatus.SERVICE_UNAVAILABLE.value()).start()) {
            assertThatThrownBy(() -> client(gateway.url(), 1).transfer(UUID.randomUUID())).isInstanceOf(GatewayUnavailableException.class);
        }
    }
}
