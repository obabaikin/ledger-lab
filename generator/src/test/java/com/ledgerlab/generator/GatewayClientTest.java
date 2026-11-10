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
import java.util.UUID;
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
        return new GatewayClient(TestData.properties(url, maxAttempts, retryBackoff), new ObjectMapper(), registry);
    }

    private double unreadable() {
        return registry.counter(GatewayClient.UNREADABLE_METRIC).count();
    }

    private static SendRecorder sends(JournalEntry entry) {
        return (attempt, startedNanos, endedNanos, result, httpStatus) -> entry.record(
            attempt == GatewayClient.FIRST_ATTEMPT ? SendKind.FIRST : SendKind.RETRY, startedNanos, endedNanos, result, httpStatus);
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
        var transfer = TestData.transfer();
        var entry = new JournalEntry(1, transfer, System.nanoTime());

        var result = client("http://localhost:" + closedPort(), 2).submit(transfer, null, () -> true, sends(entry));

        assertThat(result.outcome()).isEqualTo(SubmissionOutcome.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        var view = entry.view();
        assertThat(view.first().result()).isEqualTo(SendResult.NOT_CONNECTED);
        assertThat(view.first().httpStatus()).isNull();
        assertThat(view.resends()).singleElement().satisfies(resend -> {
            assertThat(resend.number()).isEqualTo(2);
            assertThat(resend.result()).isEqualTo(SendResult.NOT_CONNECTED);
        });
    }

    @Test
    @DisplayName("once an attempt may have reached the gateway, a later connection failure keeps it unknown")
    void once_an_attempt_may_have_reached_the_gateway_a_later_connection_failure_keeps_it_unknown() throws Exception {
        var gateway = FakeGateway.with().failFirstAttempt().start();
        var client = client(gateway.url(), 2, Duration.ofSeconds(1));
        var transfer = TestData.transfer();
        var entry = new JournalEntry(1, transfer, System.nanoTime());

        try (var submitting = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = submitting.submit(() -> client.submit(transfer, null, () -> true, sends(entry)));
            // Waiting for the request alone is not enough: closing before the 503 is written makes the first send NO_RESPONSE.
            await().atMost(Duration.ofSeconds(5)).until(() -> entry.view().first() != null);
            gateway.close(); // the retry finds no gateway
            var result = pending.get();

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.UNKNOWN);
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(entry.view().first().httpStatus()).isEqualTo(503);
            assertThat(entry.view().resends()).singleElement().satisfies(resend -> assertThat(resend.result()).isEqualTo(SendResult.NOT_CONNECTED));
        }
    }

    @Test
    void a_2xx_with_a_status_this_client_does_not_know_is_acknowledged_without_a_status() throws Exception {
        try (var gateway = FakeGateway.with().rawAnswer(it -> """
            {"transferId":"%s","runId":"%s","status":"SETTLED","acceptedAt":"%s"}""".formatted(it.transferId(), it.runId(), Instant.now())).start()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED);
            assertThat(result.status()).isNull();
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    void a_2xx_without_a_field_this_client_needs_is_acknowledged_by_its_code_and_counted_as_unreadable() throws Exception {
        try (var gateway = FakeGateway.with().rawAnswer(it -> """
            {"runId":"%s","status":"ACCEPTED","acceptedAt":"%s"}""".formatted(it.runId(), Instant.now())).start()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);

            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.ACKNOWLEDGED);
            assertThat(result.status()).isNull();
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    void a_reject_reason_this_client_does_not_know_keeps_the_status_it_knows() throws Exception {
        try (var gateway = FakeGateway.with().rawAnswer(it -> """
            {"transferId":"%s","runId":"%s","status":"%s","rejectReason":"CARD_EXPIRED","acceptedAt":"%s"}"""
            .formatted(it.transferId(), it.runId(), TransferStatus.REJECTED, Instant.now())).start()) {
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
            client(gateway.url(), 1).submit(TestData.transfer(), null);
        }
        try (var gateway = FakeGateway.with().refuseWith(HttpStatus.CONFLICT.value()).start()) {
            var result = client(gateway.url(), 1).submit(TestData.transfer(), null);
            assertThat(result.outcome()).isEqualTo(SubmissionOutcome.FAILED);
        }

        assertThat(unreadable()).isZero();
    }

    private static String statementPage(String status, String rejectReason) {
        return """
            {"entryCount":1,"items":[{"transferId":"%s","fromAccount":1,"toAccount":2,"amountCents":100,"status":"%s","rejectReason":%s}],"next":null}"""
            .formatted(UUID.randomUUID(), status, rejectReason == null ? "null" : "\"" + rejectReason + "\"");
    }

    @Test
    void a_statement_line_with_a_status_this_client_does_not_know_is_read_without_it_and_counted() {
        try (var gateway = FakeGateway.with().rawStatement(statementPage("SETTLED", null)).start()) {
            var page = client(gateway.url(), 1).statement(UUID.randomUUID(), null);

            assertThat(page.items()).singleElement().satisfies(line -> assertThat(line.status()).isNull());
            assertThat(unreadable()).isEqualTo(1.0);
        }
    }

    @Test
    void a_statement_line_with_a_reject_reason_this_client_does_not_know_keeps_its_status_and_is_counted() {
        try (var gateway = FakeGateway.with().rawStatement(statementPage(TransferStatus.REJECTED.name(), "CARD_EXPIRED")).start()) {
            var page = client(gateway.url(), 1).statement(UUID.randomUUID(), null);

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
            assertThatThrownBy(() -> client(gateway.url(), 1).statement(UUID.randomUUID(), null))
                .isInstanceOf(BadGatewayAnswerException.class);
        }
    }

    @Test
    void a_statement_page_it_can_read_in_full_is_not_counted_as_unreadable() throws Exception {
        try (var gateway = FakeGateway.with().rejectReason(RejectReason.BLOCKED_ACCOUNT).start()) {
            var client = client(gateway.url(), 1);
            var transfer = TestData.transfer();
            client.submit(transfer, Duration.ofSeconds(1));

            assertThat(client.statement(transfer.runId(), null).items()).singleElement()
                .satisfies(line -> assertThat(line.rejectReason()).isEqualTo(RejectReason.BLOCKED_ACCOUNT));
            assertThat(unreadable()).isZero();
        }
    }
}
