package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.ledger.api.PeriodController;
import com.ledgerlab.ledger.service.LedgerService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfigureTestRestTemplate
class PeriodControllerTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;

    private static String reconciliationOf(Instant periodStart) {
        return "/internal/periods/" + periodStart + "/reconciliation";
    }

    private static String checkOf(Instant periodStart) {
        return "/internal/periods/" + periodStart + "/check";
    }

    @Test
    void counts_the_transfers_of_an_ended_period_at_once_and_shows_its_check_after_it_was_asked_for() {
        openBank(3, 1_000);
        ledger.applyBatch(List.of(transfer(1, 2, 100), transfer(2, 3, 50), transfer(3, 1, 1_000_000)));
        awaitCreditsApplied();

        var counted = http.getForEntity(reconciliationOf(period.start()), Map.class);

        assertThat(counted.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = counted.getBody();
        assertThat(body.get("periodStart")).isEqualTo(period.start().toString());
        assertThat(body.get("postedTransfers")).isEqualTo(2);
        assertThat(body.get("rejectedTransfers")).isEqualTo(1);
        assertThat(body.get("rejectionsByReason")).isEqualTo(Map.of("INSUFFICIENT_FUNDS", 1));
        assertThat(body.get("poolPostings")).isEqualTo(0);
        assertThat(body.get("check")).isNull();

        assertThat(http.postForEntity(checkOf(period.start()), null, Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var check = (Map<?, ?>) http.getForObject(reconciliationOf(period.start()), Map.class).get("check");
            assertThat(check).isNotNull();
            assertThat(check.get("balanced")).isEqualTo(true);
            assertThat(check.get("accountsCompared")).isEqualTo(true);
            assertThat(check.get("journalEntries")).isEqualTo(4);
            assertThat(check.get("debitsCents")).isEqualTo(150);
        });
    }

    @Test
    void asking_for_the_check_of_a_period_again_keeps_the_one_made() {
        openBank(3, 1_000);
        applyOne(ledger, transfer(1, 2, 100));
        http.postForEntity(checkOf(period.start()), null, Void.class);
        await().atMost(Duration.ofSeconds(10)).until(() -> periodRepository.findCheck(period.start()).isPresent());
        var finishedAt = jdbc.sql("SELECT finished_at FROM period_checks").query(Timestamp.class).single();

        assertThat(http.postForEntity(checkOf(period.start()), null, Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // The second request works beside the answer: it gets the time a check takes before the kept one is looked at.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(jdbc.sql("SELECT finished_at FROM period_checks").query(Timestamp.class).list()).containsExactly(finishedAt));
    }

    @Test
    void a_period_that_has_not_ended_is_off_the_grid_or_is_older_than_the_ledger_keeps_is_unknown() {
        var current = period.next().start();
        var future = period.next().next().start();
        var offTheGrid = period.start().plusSeconds(1);
        var dropped = period.start().minus(periodsProperties.retention());

        for (var start : List.of(current, future, offTheGrid, dropped)) {
            var counted = http.getForEntity(reconciliationOf(start), Map.class);
            assertThat(counted.getStatusCode()).as("reconciliation of %s", start).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(counted.getBody().get("error")).isEqualTo(PeriodController.UNKNOWN_PERIOD);

            var asked = http.postForEntity(checkOf(start), null, Map.class);
            assertThat(asked.getStatusCode()).as("check of %s", start).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM period_checks").query(Long.class).single()).isZero();
    }

    @Test
    void what_is_not_a_time_is_a_bad_request() {
        assertThat(http.getForEntity("/internal/periods/yesterday/reconciliation", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void the_books_sum_every_balance_and_the_credits_not_yet_applied() {
        openBank(3, 1_000);
        applyOne(ledger, transfer(1, 2, 100));

        var books = http.getForEntity("/internal/books", Map.class);

        assertThat(books.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = books.getBody();
        assertThat(body.get("accounts")).isEqualTo(4);
        assertThat(body.get("openingTotalCents")).isEqualTo((int) (3_000 + POOL_OPENING_BALANCE_CENTS));
        assertThat(body.get("balanceTotalCents")).isEqualTo((int) (3_000 + POOL_OPENING_BALANCE_CENTS));
        assertThat(body.get("balanced")).isEqualTo(true);
    }
}
