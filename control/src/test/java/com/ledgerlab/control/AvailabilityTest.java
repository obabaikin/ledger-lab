package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.availability.Availability;
import com.ledgerlab.control.availability.Slo;
import com.ledgerlab.control.generator.GeneratorApi;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AvailabilityTest extends IntegrationTest {

    private static final long REQUESTS = 24_000;
    private static final Duration NOTICE = Duration.ofHours(3);

    @Autowired Availability availability;
    @Autowired DatabaseClock clock;

    private Instant minuteAgo(int minutes) {
        return clock.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(minutes));
    }

    private void minute(Instant minute, long total, long errors, long busy) {
        jdbc.sql("INSERT INTO availability_minutes (minute, total, errors, busy) VALUES (:minute, :total, :errors, :busy)")
            .param("minute", Timestamp.from(minute))
            .param("total", total)
            .param("errors", errors)
            .param("busy", busy)
            .update();
    }

    private void goodMinute(Instant minute) {
        minute(minute, REQUESTS, 0, 0);
    }

    private void badMinute(Instant minute) {
        minute(minute, REQUESTS, REQUESTS / 2, 0);
    }

    private void window(Instant from, Instant to, Instant declaredAt) {
        jdbc.sql("INSERT INTO maintenance_windows (starts_at, ends_at, declared_at) VALUES (:from, :to, :declaredAt)")
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .param("declaredAt", Timestamp.from(declaredAt))
            .update();
    }

    private Slo slo() {
        return availability.slo(clock.now());
    }

    private void generatorHandsOver(List<Map<String, Object>> minutes) {
        generator.answer("GET", GeneratorApi.MINUTES_PATH, 200, mapOf("minutes", minutes));
    }

    private static Map<String, Object> handedOver(Instant minute, long total, long errors, long busy) {
        return mapOf("minute", minute, "total", total, "errors", errors, "busy", busy);
    }

    @Test
    @DisplayName("a minute with 2 % of the requests unanswered is bad, a minute with 2 % refused as busy is good")
    void a_minute_with_2_percent_of_the_requests_unanswered_is_bad_a_minute_with_2_percent_refused_as_busy_is_good() {
        minute(minuteAgo(3), REQUESTS, REQUESTS * 2 / 100, 0);
        minute(minuteAgo(2), REQUESTS, 0, REQUESTS * 2 / 100);

        var slo = slo();

        assertThat(slo.measuredMinutes()).isEqualTo(2);
        assertThat(slo.badMinutes()).isEqualTo(1);
        assertThat(slo.availability()).isEqualTo(0.5);
        assertThat(slo.busyShare()).isEqualTo(0.01);
    }

    @Test
    @DisplayName("a minute is good with exactly 1 % unanswered and bad with one request more")
    void a_minute_is_good_with_exactly_1_percent_unanswered_and_bad_with_one_request_more() {
        minute(minuteAgo(3), REQUESTS, REQUESTS / 100, 0);
        assertThat(slo().badMinutes()).isZero();

        minute(minuteAgo(2), REQUESTS, REQUESTS / 100 + 1, 0);
        assertThat(slo().badMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("a minute without requests is not measured: it is neither good nor bad")
    void a_minute_without_requests_is_not_measured_it_is_neither_good_nor_bad() throws Exception {
        generatorHandsOver(List.of(handedOver(minuteAgo(3), REQUESTS, 0, 0), handedOver(minuteAgo(2), 0, 0, 0)));

        availability.collect();

        var slo = slo();
        assertThat(slo.measuredMinutes()).isEqualTo(1);
        assertThat(slo.badMinutes()).isZero();
        assertThat(slo.availability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("before the first measured minute there is no availability and no busy share, and the budget is whole")
    void before_the_first_measured_minute_there_is_no_availability_and_no_busy_share_and_the_budget_is_whole() {
        var slo = slo();

        assertThat(slo.availability()).isNull();
        assertThat(slo.busyShare()).isNull();
        assertThat(slo.badMinutes()).isZero();
        assertThat(slo.budgetMinutes()).isEqualTo(43);
        assertThat(slo.windowDays()).isEqualTo(30);
        assertThat(slo.target()).isEqualByComparingTo("0.999");
    }

    @Test
    @DisplayName("a bad minute in a window declared three hours ahead does not use the budget; in one declared later it does")
    void a_bad_minute_in_a_window_declared_three_hours_ahead_does_not_use_the_budget_in_one_declared_later_it_does() {
        var inTime = minuteAgo(20);
        var late = minuteAgo(10);
        badMinute(inTime);
        badMinute(late);
        window(inTime.minusSeconds(60), inTime.plusSeconds(120), inTime.minusSeconds(60).minus(NOTICE));
        window(late.minusSeconds(60), late.plusSeconds(120), late.minusSeconds(60).minus(NOTICE).plusSeconds(1));

        var slo = slo();

        assertThat(slo.badMinutes()).isEqualTo(1);
        assertThat(slo.plannedMinutes()).isEqualTo(1);
        assertThat(slo.measuredMinutes()).isEqualTo(2);
        assertThat(slo.availability()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a bad minute beside a window, or in a cancelled one, uses the budget")
    void a_bad_minute_beside_a_window_or_in_a_cancelled_one_uses_the_budget() {
        var after = minuteAgo(20);
        var cancelled = minuteAgo(10);
        badMinute(after);
        badMinute(cancelled);
        window(after.minusSeconds(600), after, after.minus(Duration.ofDays(1)));
        window(cancelled, cancelled.plusSeconds(60), cancelled.minus(Duration.ofDays(1)));
        jdbc.sql("UPDATE maintenance_windows SET cancelled_at = declared_at + interval '1 hour' WHERE starts_at = :from")
            .param("from", Timestamp.from(cancelled))
            .update();

        var slo = slo();

        assertThat(slo.badMinutes()).isEqualTo(2);
        assertThat(slo.plannedMinutes()).isZero();
    }

    @Test
    @DisplayName("13 bad minutes in 30 days use 13 of the 43 minutes of the budget, older ones do not count")
    void thirteen_bad_minutes_in_30_days_use_13_of_the_43_minutes_of_the_budget_older_ones_do_not_count() {
        var now = clock.now();
        hour(now.minus(Duration.ofDays(31)), 60, 30);
        hour(now.minus(Duration.ofDays(20)), 60, 7);
        hour(now.minus(Duration.ofDays(3)), 58, 4);
        badMinute(minuteAgo(3));
        badMinute(minuteAgo(2));
        goodMinute(minuteAgo(1));

        var slo = slo();

        assertThat(slo.badMinutes()).isEqualTo(13);
        assertThat(slo.budgetMinutes()).isEqualTo(43);
        assertThat(slo.measuredMinutes()).isEqualTo(60 + 58 + 3);
    }

    private void hour(Instant within, int measured, int bad) {
        jdbc.sql(
            """
            INSERT INTO availability_hours (hour, measured_minutes, bad_minutes, planned_minutes, total, errors, busy)
            VALUES (date_trunc('hour', CAST(:within AS timestamptz), 'UTC'), :measured, :bad, 0, :total, 0, 0)
            """)
            .param("within", Timestamp.from(within))
            .param("measured", measured)
            .param("bad", bad)
            .param("total", measured * REQUESTS)
            .update();
    }

    @Test
    @DisplayName("control asks the generator for the minutes after the last one it holds and keeps each minute once")
    void control_asks_the_generator_for_the_minutes_after_the_last_one_it_holds_and_keeps_each_minute_once() throws Exception {
        var first = minuteAgo(3);
        var second = minuteAgo(2);
        generatorHandsOver(List.of(handedOver(first, REQUESTS, 0, 0)));
        availability.collect();
        generatorHandsOver(List.of(handedOver(first, REQUESTS, REQUESTS, 0), handedOver(second, REQUESTS, REQUESTS, 0)));

        availability.collect();

        var asked = generator.calls.stream().filter(it -> it.path().equals(GeneratorApi.MINUTES_PATH)).toList();
        assertThat(asked).hasSize(2);
        assertThat(jdbc.sql("SELECT minute, errors FROM availability_minutes ORDER BY minute").query().listOfRows())
            .extracting(it -> it.get("errors"))
            .containsExactly(0L, REQUESTS);
        assertThat(slo().badMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("an hour that is over is added up, and the numbers stay the same once its minutes are deleted")
    void an_hour_that_is_over_is_added_up_and_the_numbers_stay_the_same_once_its_minutes_are_deleted() throws Exception {
        var lastHour = clock.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        goodMinute(lastHour.plusSeconds(60));
        badMinute(lastHour.plusSeconds(120));
        minute(lastHour.plusSeconds(180), REQUESTS, 0, REQUESTS / 4);
        goodMinute(minuteAgo(0).minusSeconds(60));
        generatorHandsOver(List.of());
        var before = slo();

        availability.collect();

        assertThat(jdbc.sql("SELECT measured_minutes, bad_minutes, planned_minutes FROM availability_hours").query().listOfRows())
            .containsExactly(Map.of("measured_minutes", 3, "bad_minutes", 1, "planned_minutes", 0));
        assertThat(slo()).isEqualTo(before);
        jdbc.sql("DELETE FROM availability_minutes WHERE minute < :hourEnd").param("hourEnd", Timestamp.from(lastHour.plus(Duration.ofHours(1)))).update();
        assertThat(slo()).isEqualTo(before);
    }

    @Test
    @DisplayName("minutes older than their retention are deleted by whole hours, the newer ones stay")
    void minutes_older_than_their_retention_are_deleted_by_whole_hours_the_newer_ones_stay() {
        var now = clock.now();
        goodMinute(now.minus(Duration.ofHours(50)).truncatedTo(ChronoUnit.MINUTES));
        goodMinute(minuteAgo(5));

        assertThat(availability.deleteOldMinutes(now)).isEqualTo(1);

        assertThat(jdbc.sql("SELECT count(*) FROM availability_minutes").query(Long.class).single()).isEqualTo(1);
    }
}
