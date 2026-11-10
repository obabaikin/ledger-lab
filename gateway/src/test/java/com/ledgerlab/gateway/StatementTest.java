package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.gateway.api.GatewayErrors;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

class StatementTest extends IntegrationTest {

    @Autowired DataSource dataSource;

    private record Submitted(Map<String, Object> request, HttpStatusCode status) {
    }

    private record ClosedWith(HttpStatusCode status, List<Object> transferIds, List<Long> entryCounts) {
    }

    private ResponseEntity<Map<String, Object>> close(UUID runId) {
        return post("/internal/runs/" + runId + "/close", null);
    }

    private ResponseEntity<Map<String, Object>> statement(UUID runId) {
        return statement(runId, "");
    }

    private ResponseEntity<Map<String, Object>> statement(UUID runId, String query) {
        return get("/v1/runs/" + runId + "/statement" + query);
    }

    private List<Map<String, Object>> allPages(UUID runId, int limit) {
        var pages = new ArrayList<Map<String, Object>>();
        Object after = null;
        do {
            var page = statement(runId, "?limit=" + limit + (after == null ? "" : "&after=" + after)).getBody();
            pages.add(page);
            after = page.get("next");
        } while (after != null);
        return pages;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private List<List<Map<String, Object>>> allItems(UUID runId, int limit) {
        return allPages(runId, limit).stream().map(StatementTest::itemsOf).toList();
    }

    private List<Map<String, Object>> allLines(UUID runId, int limit) {
        return allItems(runId, limit).stream().flatMap(List::stream).toList();
    }

    private List<Long> entryCounts(UUID runId, int limit) {
        return allPages(runId, limit).stream().map(page -> ((Number) page.get("entryCount")).longValue()).toList();
    }

    @Test
    @DisplayName("the statement of a closed period lists every transfer in acceptance order, page by page")
    void the_statement_of_a_closed_period_lists_every_transfer_in_acceptance_order_page_by_page() {
        var runId = UUID.randomUUID();
        var sent = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < 5; i++) {
            var request = body(UUID.randomUUID(), runId, 1, 2 + i, 100L + i);
            submit(request);
            sent.add(request);
        }

        var closed = close(runId);
        var pages = allItems(runId, 2);

        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pages).extracting(List::size).containsExactly(2, 2, 1);
        var items = pages.stream().flatMap(List::stream).toList();
        assertThat(items).extracting(item -> item.get("transferId"))
            .containsExactlyInAnyOrderElementsOf(sent.stream().<Object>map(request -> request.get("transferId").toString()).toList());
        assertThat(items).extracting(item -> (String) item.get("acceptedAt")).isSorted();
        var first = sent.stream().filter(request -> request.get("transferId").toString().equals(items.get(0).get("transferId"))).findFirst().orElseThrow();
        assertThat(items.get(0)).containsEntry("fromAccount", 1).containsEntry("toAccount", first.get("toAccount"))
            .containsEntry("amountCents", ((Long) first.get("amountCents")).intValue())
            .containsKeys("status", "rejectReason", "acceptedAt", "resultedAt").doesNotContainKey("publishedAt");
        assertThat(statement(runId).getBody().get("closedAt")).isEqualTo(closed.getBody().get("closedAt"));
        assertThat(entryCounts(runId, 2)).containsExactly(5L, 5L, 5L);
    }

    @Test
    void the_entry_count_counts_rejected_and_unfinished_lines_alike() throws Exception {
        var runId = UUID.randomUUID();
        var rejected = bodyOfRun(runId);
        submit(rejected);
        submit(bodyOfRun(runId));
        publishResult(runId, (UUID) rejected.get("transferId"), TransferOutcome.REJECTED, RejectReason.INSUFFICIENT_FUNDS);
        await().atMost(Duration.ofSeconds(10)).until(() ->
            "REJECTED".equals(get("/v1/transfers/" + rejected.get("transferId")).getBody().get("status")));

        close(runId);

        assertThat(entryCounts(runId, 100)).containsExactly(2L);
    }

    @Test
    @DisplayName("the close answer carries no entry count, only the statement does")
    void the_close_answer_carries_no_entry_count_only_the_statement_does() {
        var closed = close(UUID.randomUUID()).getBody();

        assertThat(closed.keySet()).containsExactlyInAnyOrder("runId", "closedAt");
    }

    @Test
    void there_is_no_statement_before_the_period_is_closed() {
        var runId = UUID.randomUUID();
        submit(bodyOfRun(runId));

        var open = statement(runId);
        var unknown = statement(UUID.randomUUID());

        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(open.getBody().get("error")).isEqualTo(GatewayErrors.PERIOD_NOT_CLOSED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void closing_twice_keeps_the_first_closing_time_and_an_unknown_run_gets_an_empty_statement() {
        var runId = UUID.randomUUID();

        var first = close(runId);
        var second = close(runId);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("closedAt")).isEqualTo(first.getBody().get("closedAt"));
        var empty = statement(runId).getBody();
        assertThat(itemsOf(empty)).isEmpty();
        assertThat(empty.get("next")).isNull();
        assertThat(empty.get("entryCount")).isEqualTo(0);
    }

    @Test
    void after_closing_a_new_key_is_refused_with_409_while_a_known_key_is_answered_as_before() {
        var runId = UUID.randomUUID();
        var known = bodyOfRun(runId);
        submit(known);
        close(runId);

        var repeated = submit(known);
        var withOtherAmount = new HashMap<>(known);
        withOtherAmount.put("amountCents", 999);
        var otherPayload = submit(withOtherAmount);
        var newKey = submit(bodyOfRun(runId));

        assertThat(repeated.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(otherPayload.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(newKey.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(newKey.getBody().get("error")).isEqualTo(GatewayErrors.PERIOD_CLOSED);
        assertThat(allLines(runId, 100)).extracting(line -> line.get("transferId")).containsExactly(known.get("transferId").toString());
        assertThat(entryCounts(runId, 100)).containsExactly(1L);
    }

    @Test
    void submissions_racing_a_close_are_in_the_statement_exactly_when_they_were_accepted() throws Exception {
        try (var pool = Executors.newFixedThreadPool(9)) {
            for (var round = 0; round < 10; round++) {
                var runId = UUID.randomUUID();
                var gate = new CountDownLatch(1);
                var answers = new ArrayList<Future<List<Submitted>>>();
                for (var chunk = 0; chunk < 8; chunk++) {
                    var requests = new ArrayList<Map<String, Object>>();
                    for (var i = 0; i < 5; i++) {
                        requests.add(bodyOfRun(runId));
                    }
                    answers.add(pool.submit(() -> {
                        gate.await();
                        return requests.stream().map(request -> new Submitted(request, submit(request).getStatusCode())).toList();
                    }));
                }
                var closing = pool.submit(() -> {
                    gate.await();
                    Thread.sleep(5);
                    var closed = close(runId);
                    return new ClosedWith(
                        closed.getStatusCode(),
                        allLines(runId, 7).stream().map(line -> line.get("transferId")).toList(),
                        entryCounts(runId, 7));
                });
                gate.countDown();

                var outcomes = new ArrayList<Submitted>();
                for (var answer : answers) {
                    outcomes.addAll(answer.get());
                }
                var closedWith = closing.get();
                assertThat(closedWith.status()).isEqualTo(HttpStatus.OK);
                assertThat(outcomes).extracting(Submitted::status).isSubsetOf(HttpStatus.ACCEPTED, HttpStatus.OK, HttpStatus.CONFLICT);
                var acceptedIds = outcomes.stream()
                    .filter(it -> !HttpStatus.CONFLICT.equals(it.status()))
                    .<Object>map(it -> it.request().get("transferId").toString())
                    .toList();
                assertThat(closedWith.transferIds()).containsExactlyInAnyOrderElementsOf(acceptedIds);
                assertThat(closedWith.entryCounts()).allMatch(count -> count == acceptedIds.size());
            }
        }
    }

    @Test
    @DisplayName("close waits for an insert in flight, so that insert is in the statement")
    void close_waits_for_an_insert_in_flight_so_that_insert_is_in_the_statement() throws Exception {
        var runId = UUID.randomUUID();
        var transferId = UUID.randomUUID();
        try (var insert = dataSource.getConnection()) {
            insert.setAutoCommit(false);
            holdRunLockShared(insert, runId);
            try (var statement = insert.prepareStatement(
                "INSERT INTO transfers (transfer_id, run_id, from_account, to_account, amount_cents, status) VALUES (?, ?, 1, 2, 100, 'ACCEPTED')")) {
                statement.setObject(1, transferId);
                statement.setObject(2, runId);
                statement.executeUpdate();
            }

            var closing = CompletableFuture.supplyAsync(() -> close(runId));
            Thread.sleep(300);
            assertThat(closing).isNotDone();
            insert.commit();

            assertThat(closing.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var statement = statement(runId).getBody();
        var items = itemsOf(statement);
        assertThat(items).extracting(item -> item.get("transferId")).containsExactly(transferId.toString());
        assertThat(statement.get("entryCount")).isEqualTo(1);
        assertThat(Instant.parse((String) items.get(0).get("acceptedAt"))).isBefore(Instant.parse((String) statement.get("closedAt")));
    }

    @Test
    void a_submission_arriving_while_close_waits_is_refused_once_the_close_commits() throws Exception {
        var runId = UUID.randomUUID();
        var late = bodyOfRun(runId);
        try (var insert = dataSource.getConnection()) {
            insert.setAutoCommit(false);
            holdRunLockShared(insert, runId);
            var waitingBefore = waitingAdvisoryLocks();

            var closing = CompletableFuture.supplyAsync(() -> close(runId));
            await().atMost(Duration.ofMillis(800)).until(() -> waitingAdvisoryLocks() > waitingBefore);
            var submitting = CompletableFuture.supplyAsync(() -> submit(late));
            await().atMost(Duration.ofMillis(800)).until(() -> waitingAdvisoryLocks() > waitingBefore + 1);
            insert.commit();

            assertThat(closing.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(submitting.get().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(allLines(runId, 100)).isEmpty();
    }

    private int waitingAdvisoryLocks() throws SQLException {
        try (var c = dataSource.getConnection();
            var st = c.createStatement();
            var rs = st.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void close_gives_up_with_503_when_an_insert_in_flight_outlasts_the_lock_timeout() throws SQLException {
        var runId = UUID.randomUUID();
        try (var insert = dataSource.getConnection()) {
            insert.setAutoCommit(false);
            holdRunLockShared(insert, runId);

            var timedOut = close(runId);

            assertThat(timedOut.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(statement(runId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            insert.rollback();
        }
        assertThat(close(runId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void an_invalid_limit_or_a_cursor_from_another_run_is_a_bad_request() {
        var runId = UUID.randomUUID();
        var otherRun = UUID.randomUUID();
        var foreignRequest = bodyOfRun(otherRun);
        submit(foreignRequest);
        var foreign = foreignRequest.get("transferId");
        close(runId);

        assertThat(statement(runId, "?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statement(runId, "?limit=1001").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statement(runId, "?after=" + foreign).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statement(runId, "?limit=1000").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deleting_a_run_removes_its_period_too() {
        var runId = UUID.randomUUID();
        submit(bodyOfRun(runId));
        close(runId);

        http.delete("/internal/runs/" + runId);

        assertThat(statement(runId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(submit(bodyOfRun(runId)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private static void holdRunLockShared(Connection connection, UUID runId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))")) {
            statement.setString(1, runId.toString());
            statement.executeQuery().close();
        }
    }
}
