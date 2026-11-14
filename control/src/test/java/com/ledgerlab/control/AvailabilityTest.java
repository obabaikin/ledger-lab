package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.availability.Availability;
import com.ledgerlab.control.availability.AvailabilityRepository;
import com.ledgerlab.control.availability.Slo;
import com.ledgerlab.control.generator.GeneratorApi;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AvailabilityTest extends IntegrationTest {

    private static final long REQUESTS = 24_000;
    private static final Duration NOTICE = Duration.ofHours(3);

    private static final long TRANSFERS = 10_000;
    private static final int GUARANTEED_TPS = 1300;

    @Autowired Availability availability;
    @Autowired AvailabilityRepository repository;
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

    private void minute(Instant minute, long errors, @Nullable Long transfers, @Nullable Long slow, @Nullable Boolean restarted) {
        jdbc.sql(
            """
            INSERT INTO availability_minutes (minute, total, errors, busy, transfers, slow, restarted)
            VALUES (:minute, :total, :errors, 0, :transfers, :slow, :restarted)
            """)
            .param("minute", Timestamp.from(minute))
            .param("total", REQUESTS)
            .param("errors", errors)
            .param("transfers", transfers)
            .param("slow", slow)
            .param("restarted", restarted)
            .update();
    }

    /** Bad for both objectives: half the requests unanswered, half the transfers slow. */
    private void badForBoth(Instant minute) {
        minute(minute, REQUESTS / 2, TRANSFERS, TRANSFERS / 2, false);
    }

    private void restartedBadForBoth(Instant minute) {
        minute(minute, REQUESTS / 2, TRANSFERS, TRANSFERS / 2, true);
    }

    private void chaos(Instant at) {
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (:at, ARRAY['ledger-0'])").param("at", Timestamp.from(at)).update();
    }

    private void pulse(Instant at, int tps, int seconds) {
        jdbc.sql("INSERT INTO load_pulses (at, tps, seconds, until) VALUES (:at, :tps, :seconds, :until)")
            .param("at", Timestamp.from(at))
            .param("tps", tps)
            .param("seconds", seconds)
            .param("until", Timestamp.from(at.plusSeconds(seconds)))
            .update();
    }

    private Availability.Objectives objectives() {
        return availability.objectives(clock.now());
    }

    private AvailabilityRepository.Counts counts() {
        return repository.countsSince(clock.now().minus(Duration.ofDays(30)), new AvailabilityRepository.Rules(
            props.availability().goodMinuteAnsweredPercent(), NOTICE, props.freshness().badSharePercent(), GUARANTEED_TPS,
            props.outside().chaosAftermath(), props.outside().loadAftermath()));
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
        var freshness = objectives().freshness();
        assertThat(freshness.budgetMinutes()).isEqualTo(43);
        assertThat(freshness.thresholdMs()).isEqualTo(2000);
        assertThat(freshness.target()).isEqualByComparingTo("0.999");
        assertThat(freshness.badMinutes()).isZero();
    }

    @Test
    @DisplayName("a minute is fresh with exactly 1 % of its transfers slow and bad for freshness with one transfer more")
    void a_minute_is_fresh_with_exactly_1_percent_of_its_transfers_slow_and_bad_for_freshness_with_one_transfer_more() {
        minute(minuteAgo(3), 0, TRANSFERS, TRANSFERS / 100, false);
        assertThat(objectives().freshness().badMinutes()).isZero();

        minute(minuteAgo(2), 0, TRANSFERS, TRANSFERS / 100 + 1, false);
        assertThat(objectives().freshness().badMinutes()).isEqualTo(1);
        assertThat(slo().badMinutes()).isZero();
    }

    @Test
    @DisplayName("a minute without transfers, or not read from monitoring, is not measured for freshness")
    void a_minute_without_transfers_or_not_read_from_monitoring_is_not_measured_for_freshness() {
        minute(minuteAgo(4), 0, null, null, null);
        minute(minuteAgo(3), 0, 0L, 0L, false);
        minute(minuteAgo(2), 0, TRANSFERS, 0L, false);

        var counts = counts();

        assertThat(counts.measuredMinutes()).isEqualTo(3);
        assertThat(counts.freshMeasured()).isEqualTo(1);
        assertThat(counts.freshBad()).isZero();
    }

    @Test
    @DisplayName("a bad minute in a window declared in time is planned for both objectives")
    void a_bad_minute_in_a_window_declared_in_time_is_planned_for_both_objectives() {
        var inWindow = minuteAgo(20);
        badForBoth(inWindow);
        window(inWindow, inWindow.plusSeconds(60), inWindow.minus(NOTICE));

        var objectives = objectives();

        assertThat(objectives.slo().plannedMinutes()).isEqualTo(1);
        assertThat(objectives.slo().badMinutes()).isZero();
        assertThat(objectives.freshness().plannedMinutes()).isEqualTo(1);
        assertThat(objectives.freshness().badMinutes()).isZero();
    }

    @Test
    @DisplayName("bad minutes during a crash and the 60 s after it are outside for both objectives; the minute after them is judged")
    void bad_minutes_during_a_crash_and_the_60_s_after_it_are_outside_for_both_objectives_the_minute_after_them_is_judged() {
        var crashed = minuteAgo(20);
        chaos(crashed.plusSeconds(10));
        badForBoth(crashed);
        badForBoth(crashed.plusSeconds(60));
        badForBoth(crashed.plusSeconds(120));

        var objectives = objectives();

        assertThat(objectives.slo().outsideMinutes()).isEqualTo(2);
        assertThat(objectives.slo().badMinutes()).isEqualTo(1);
        assertThat(objectives.freshness().outsideMinutes()).isEqualTo(2);
        assertThat(objectives.freshness().badMinutes()).isEqualTo(1);
        assertThat(objectives.slo().availability()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("bad minutes during a pulse above the promised speed and the 30 s after it are outside; the minute after them is judged")
    void bad_minutes_during_a_pulse_above_the_promised_speed_and_the_30_s_after_it_are_outside_the_minute_after_them_is_judged() {
        var pulsed = minuteAgo(20);
        pulse(pulsed.plusSeconds(10), 2000, 30);
        badForBoth(pulsed);
        badForBoth(pulsed.plusSeconds(60));
        badForBoth(pulsed.plusSeconds(120));

        var objectives = objectives();

        assertThat(objectives.slo().outsideMinutes()).isEqualTo(2);
        assertThat(objectives.slo().badMinutes()).isEqualTo(1);
        assertThat(objectives.freshness().outsideMinutes()).isEqualTo(2);
        assertThat(objectives.freshness().badMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("a bad minute during a pulse at the promised speed is judged")
    void a_bad_minute_during_a_pulse_at_the_promised_speed_is_judged() {
        var pulsed = minuteAgo(20);
        pulse(pulsed.plusSeconds(10), GUARANTEED_TPS, 30);
        badForBoth(pulsed);

        var objectives = objectives();

        assertThat(objectives.slo().badMinutes()).isEqualTo(1);
        assertThat(objectives.slo().outsideMinutes()).isZero();
        assertThat(objectives.freshness().badMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("a service restarted in place during a crash makes every bad minute of the crash judged, those before the restart too")
    void a_service_restarted_in_place_during_a_crash_makes_every_bad_minute_of_the_crash_judged_those_before_the_restart_too() {
        var crashed = minuteAgo(20);
        chaos(crashed.plusSeconds(10));
        badForBoth(crashed);
        restartedBadForBoth(crashed.plusSeconds(60));
        var pulsed = minuteAgo(10);
        pulse(pulsed.plusSeconds(10), 2000, 30);
        badForBoth(pulsed);
        restartedBadForBoth(pulsed.plusSeconds(60));

        var objectives = objectives();

        assertThat(objectives.slo().badMinutes()).isEqualTo(4);
        assertThat(objectives.slo().outsideMinutes()).isZero();
        assertThat(objectives.freshness().badMinutes()).isEqualTo(4);
        assertThat(objectives.freshness().outsideMinutes()).isZero();
    }

    @Test
    @DisplayName("a good minute during a crash stays good and measured")
    void a_good_minute_during_a_crash_stays_good_and_measured() {
        var crashed = minuteAgo(20);
        chaos(crashed.plusSeconds(10));
        goodMinute(crashed);
        badMinute(minuteAgo(5));

        var slo = slo();

        assertThat(slo.measuredMinutes()).isEqualTo(2);
        assertThat(slo.badMinutes()).isEqualTo(1);
        assertThat(slo.availability()).isEqualTo(0.5);
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

    @Test
    @DisplayName("the 30 days start on the hour: the hour they begin in counts whole, the hour before it does not")
    void the_30_days_start_on_the_hour_the_hour_they_begin_in_counts_whole_the_hour_before_it_does_not() {
        var now = clock.now();
        var windowStart = now.minus(Duration.ofDays(30));
        hour(windowStart, 60, 5);
        hour(windowStart.minus(Duration.ofHours(1)), 60, 20);

        var slo = availability.slo(now);

        assertThat(slo.badMinutes()).isEqualTo(5);
        assertThat(slo.measuredMinutes()).isEqualTo(60);
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
        badMinute(lastHour.plusSeconds(600));
        window(lastHour.plusSeconds(600), lastHour.plusSeconds(900), lastHour.minus(Duration.ofDays(1)));
        goodMinute(clock.now().truncatedTo(ChronoUnit.HOURS));
        generatorHandsOver(List.of());
        var before = slo();

        availability.collect();

        assertThat(jdbc.sql("SELECT measured_minutes, bad_minutes, planned_minutes FROM availability_hours").query().listOfRows())
            .containsExactly(Map.of("measured_minutes", 4, "bad_minutes", 1, "planned_minutes", 1));
        assertThat(slo()).isEqualTo(before);
        jdbc.sql("DELETE FROM availability_minutes WHERE minute < :hourEnd").param("hourEnd", Timestamp.from(lastHour.plus(Duration.ofHours(1)))).update();
        assertThat(slo()).isEqualTo(before);
    }

    @Test
    @DisplayName("an hour that is over keeps both objectives' planned, outside and bad minutes once its minutes are deleted")
    void an_hour_that_is_over_keeps_both_objectives_planned_outside_and_bad_minutes_once_its_minutes_are_deleted() throws Exception {
        var lastHour = clock.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        badForBoth(lastHour.plusSeconds(60));
        minute(lastHour.plusSeconds(120), 0, TRANSFERS, TRANSFERS / 2, false);
        badForBoth(lastHour.plusSeconds(600));
        window(lastHour.plusSeconds(600), lastHour.plusSeconds(660), lastHour.minus(Duration.ofDays(1)));
        badForBoth(lastHour.plusSeconds(1200));
        chaos(lastHour.plusSeconds(1200));
        minute(lastHour.plusSeconds(1800), 0, null, null, null);
        generatorHandsOver(List.of());
        prometheus.status = 503;
        var before = objectives();

        availability.collect();

        assertThat(jdbc.sql(
            "SELECT measured_minutes, bad_minutes, planned_minutes, outside_minutes, fresh_measured, fresh_bad, fresh_planned, fresh_outside FROM availability_hours")
            .query().listOfRows())
            .containsExactly(Map.of(
                "measured_minutes", 5, "bad_minutes", 1, "planned_minutes", 1, "outside_minutes", 1,
                "fresh_measured", 4, "fresh_bad", 2, "fresh_planned", 1, "fresh_outside", 1));
        assertThat(objectives()).isEqualTo(before);
        jdbc.sql("DELETE FROM availability_minutes WHERE minute < :hourEnd").param("hourEnd", Timestamp.from(lastHour.plus(Duration.ofHours(1)))).update();
        assertThat(objectives()).isEqualTo(before);
        assertThat(before.freshness().badMinutes()).isEqualTo(2);
        assertThat(before.slo().outsideMinutes()).isEqualTo(1);
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
