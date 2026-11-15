package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.reporting.api.ReportingErrors;
import com.ledgerlab.reporting.domain.Period;
import com.ledgerlab.reporting.domain.PeriodStats;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@AutoConfigureTestRestTemplate
class PeriodStatsTest extends IntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    @Autowired TestRestTemplate http;

    private PeriodStats stats(Period period) {
        return http.getForObject("/internal/periods/" + period.start() + "/stats", PeriodStats.class);
    }

    private ResponseEntity<Map<String, Object>> get(String url) {
        return http.exchange(url, HttpMethod.GET, null, JSON_OBJECT);
    }

    @Test
    void a_period_with_no_events_has_zeros() {
        var ended = lastEndedPeriod();

        var stats = stats(ended);

        assertThat(stats.periodStart()).isEqualTo(ended.start());
        assertThat(stats.accepted()).isZero();
        assertThat(stats.commandsSeen()).isZero();
        assertThat(stats.posted()).isZero();
        assertThat(stats.rejected()).isZero();
        assertThat(stats.postedAmountCents()).isZero();
        assertThat(stats.rejectionsByReason()).isEmpty();
    }

    @Test
    @DisplayName("a period's numbers count accepted, posted, rejected by reason and the posted amount")
    void a_periods_numbers_count_accepted_posted_rejected_by_reason_and_the_posted_amount() {
        var ended = lastEndedPeriod();
        var t0 = ended.start();
        var a = command(100, t0.plusMillis(1));
        var b = command(200, t0.plusMillis(2));
        var c = command(300, t0.plusSeconds(70));
        var d = command(400, t0.plusSeconds(70));
        var e = command(500, t0.plusSeconds(71));
        store(a, b, c, d, e);
        store(
            posted(a, t0.plusMillis(10)),
            posted(b, t0.plusMillis(20)),
            rejected(c, RejectReason.INSUFFICIENT_FUNDS, t0.plusSeconds(71)),
            rejected(d, RejectReason.INSUFFICIENT_FUNDS, t0.plusSeconds(71)),
            rejected(e, RejectReason.BLOCKED_ACCOUNT, t0.plusSeconds(72)));

        var stats = stats(ended);

        assertThat(stats.accepted()).isEqualTo(5);
        assertThat(stats.commandsSeen()).isEqualTo(5);
        assertThat(stats.posted()).isEqualTo(2);
        assertThat(stats.rejected()).isEqualTo(3);
        assertThat(stats.postedAmountCents()).isEqualTo(300);
        assertThat(stats.rejectionsByReason()).containsExactly(
            entry(RejectReason.BLOCKED_ACCOUNT.name(), 1L), entry(RejectReason.INSUFFICIENT_FUNDS.name(), 2L));
    }

    @Test
    @DisplayName("a transfer accepted before the period's end and booked after it counts in the period it was accepted in")
    void a_transfer_accepted_before_the_periods_end_and_booked_after_it_counts_in_the_period_it_was_accepted_in() {
        var ended = lastEndedPeriod();
        var lastMicrosecond = command(100, ended.end().minusNanos(1_000));
        var firstOfTheNext = command(200, ended.end());
        store(lastMicrosecond, firstOfTheNext);
        store(posted(lastMicrosecond, ended.end().plusSeconds(2)), posted(firstOfTheNext, ended.end().plusSeconds(2)));

        var endedStats = stats(ended);
        var nextStats = stats(ended.next());

        assertThat(endedStats.accepted()).isEqualTo(1);
        assertThat(endedStats.posted()).isEqualTo(1);
        assertThat(endedStats.postedAmountCents()).isEqualTo(100);
        assertThat(nextStats.accepted()).isEqualTo(1);
        assertThat(nextStats.posted()).isEqualTo(1);
        assertThat(nextStats.postedAmountCents()).isEqualTo(200);
    }

    @Test
    @DisplayName("a result seen before its command counts the transfer as accepted once, and its amount when the command comes")
    void a_result_seen_before_its_command_counts_the_transfer_as_accepted_once_and_its_amount_when_the_command_comes() {
        var ended = lastEndedPeriod();
        var t0 = ended.start();
        var early = command(100, t0.plusMillis(500));
        var late = command(200, t0.plusMillis(600));
        var lateRejected = command(400, t0.plusMillis(700));
        store(early);
        store(posted(early, t0.plusMillis(1_100)), posted(late, t0.plusMillis(2_100)), rejected(lateRejected, RejectReason.BLOCKED_ACCOUNT, t0.plusMillis(2_200)));

        var before = stats(ended);

        assertThat(before.accepted()).isEqualTo(3);
        assertThat(before.commandsSeen()).isEqualTo(1);
        assertThat(before.posted()).isEqualTo(2);
        assertThat(before.rejected()).isEqualTo(1);
        assertThat(before.postedAmountCents()).isEqualTo(100);

        store(late, lateRejected);

        var complete = stats(ended);
        assertThat(complete.accepted()).isEqualTo(3);
        assertThat(complete.commandsSeen()).isEqualTo(3);
        assertThat(complete.posted()).isEqualTo(2);
        assertThat(complete.postedAmountCents()).isEqualTo(300);
    }

    @Test
    void storing_the_same_events_again_changes_nothing() {
        var ended = lastEndedPeriod();
        var t0 = ended.start();
        var a = command(100, t0);
        var b = command(200, t0);
        store(a, a, b);
        store(posted(a, t0.plusMillis(5)), rejected(b, RejectReason.INSUFFICIENT_FUNDS, t0.plusMillis(5)));
        var before = stats(ended);
        assertThat(before.accepted()).isEqualTo(2);

        store(a, b);
        store(posted(a, t0.plusMillis(5)), posted(a, t0.plusMillis(5)), rejected(b, RejectReason.INSUFFICIENT_FUNDS, t0.plusMillis(5)));

        var after = stats(ended);
        assertThat(after).usingRecursiveComparison().ignoringFields("at").isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfer_stats", Long.class)).isEqualTo(2);
    }

    @Test
    void the_current_period_is_answered_without_naming_its_start() {
        var current = currentPeriod();
        var transfer = command(100, Instant.now());
        store(transfer);

        var stats = http.getForObject("/internal/periods/current/stats", PeriodStats.class);

        // The period may have changed between the two lines above; then the transfer is in the one before.
        assertThat(stats.periodStart()).isIn(current.start(), current.end());
        if (stats.periodStart().equals(current.start())) {
            assertThat(stats.accepted()).isEqualTo(1);
            assertThat(stats(current)).usingRecursiveComparison().ignoringFields("at").isEqualTo(stats);
        }
    }

    @Test
    void a_start_off_the_grid_a_period_not_begun_and_one_older_than_the_retention_are_unknown() {
        var current = currentPeriod();
        var oldestHeld = Period.containing(Instant.now().minus(props.periods().retention()), props.periods().length());

        var offGrid = get("/internal/periods/" + current.start().plusSeconds(1) + "/stats");
        var notBegun = get("/internal/periods/" + current.end() + "/stats");
        var tooOld = get("/internal/periods/" + oldestHeld.previous().start() + "/stats");

        for (var answer : List.of(offGrid, notBegun, tooOld)) {
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(answer.getBody()).containsEntry("error", ReportingErrors.UNKNOWN_PERIOD);
        }
        assertThat(get("/internal/periods/" + oldestHeld.start() + "/stats").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
