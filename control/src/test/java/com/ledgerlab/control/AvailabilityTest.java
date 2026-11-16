package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.availability.Availability;
import com.ledgerlab.control.availability.AvailabilityRepository;
import com.ledgerlab.control.availability.DayCount;
import com.ledgerlab.control.availability.Posting;
import com.ledgerlab.control.availability.Slo;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.periods.PeriodLoad;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    /** As the chart names the ledger's first pod, in a crash's list and in monitoring alike. */
    private static final String LEDGER = "ledger-0";
    private static final String GATEWAY = "gateway-7d9f-abcde";

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

    private void postedMinute(Instant minute, long errors, @Nullable Long transfers, @Nullable Long withinTypical, @Nullable Long withinTail) {
        jdbc.sql(
            """
            INSERT INTO availability_minutes (minute, total, errors, busy, transfers, within_typical, within_tail)
            VALUES (:minute, :total, :errors, 0, :transfers, :withinTypical, :withinTail)
            """)
            .param("minute", Timestamp.from(minute))
            .param("total", REQUESTS)
            .param("errors", errors)
            .param("transfers", transfers)
            .param("withinTypical", withinTypical)
            .param("withinTail", withinTail)
            .update();
    }

    /** Bad for availability, half the requests unanswered; and half the transfers over 3 s, a quarter over 5 s. */
    private void badForBoth(Instant minute) {
        postedMinute(minute, REQUESTS / 2, TRANSFERS, TRANSFERS / 2, TRANSFERS * 3 / 4);
    }

    private Posting posting() {
        return objectives().posting();
    }

    private static Posting.Target typical(Posting posting) {
        return posting.targets().getFirst();
    }

    private static Posting.Target tail(Posting posting) {
        return posting.targets().getLast();
    }

    private void started(String instance, Instant at) {
        repository.addStarts(List.of(new AvailabilityRepository.ServiceStart(instance, at)));
    }

    /** A pod that has run for a day, so that its next start is a restart in place. */
    private void running(String instance) {
        started(instance, clock.now().minus(Duration.ofDays(1)));
    }

    private void chaos(Instant at) {
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (:at, ARRAY[:crashed])").param("at", Timestamp.from(at)).param("crashed", LEDGER).update();
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
    @DisplayName("without a posted transfer in 30 days both shares are null, not 100 %, and nothing says since when")
    void without_a_posted_transfer_in_30_days_both_shares_are_null_not_100_percent_and_nothing_says_since_when() {
        postedMinute(minuteAgo(4), 0, null, null, null);
        postedMinute(minuteAgo(3), 0, 0L, 0L, 0L);

        var posting = posting();

        assertThat(posting.judged()).isZero();
        assertThat(posting.since()).isNull();
        assertThat(posting.windowDays()).isEqualTo(30);
        assertThat(posting.targets()).extracting(Posting.Target::thresholdMs).containsExactly(3000L, 5000L);
        assertThat(posting.targets()).extracting(Posting.Target::target).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("0.95"), new BigDecimal("0.99"));
        assertThat(posting.targets()).allSatisfy(target -> {
            assertThat(target.share()).isNull();
            assertThat(target.budgetUsed()).isNull();
        });
    }

    @Test
    @DisplayName("1000 transfers, 40 over 3 s and 5 over 5 s, no events: 96 % within 3 s and 99.5 % within 5 s, 80 % and 50 % of the budgets used")
    void a_thousand_transfers_40_over_3_s_and_5_over_5_s_no_events_96_percent_within_3_s_and_99_5_percent_within_5_s() {
        postedMinute(minuteAgo(3), 0, 600L, 600L - 30, 600L - 3);
        postedMinute(minuteAgo(2), 0, 400L, 400L - 10, 400L - 2);

        var posting = posting();

        assertThat(posting.judged()).isEqualTo(1000);
        assertThat(typical(posting).within()).isEqualTo(960);
        assertThat(typical(posting).share()).isEqualTo(0.96);
        assertThat(typical(posting).budgetUsed()).isEqualTo(0.8);
        assertThat(tail(posting).share()).isEqualTo(0.995);
        assertThat(tail(posting).budgetUsed()).isEqualTo(0.5);
        assertThat(slo().badMinutes()).isZero();
    }

    @Test
    @DisplayName("exactly 95.00 % within 3 s uses exactly the whole budget: the target is met")
    void exactly_95_percent_within_3_s_uses_exactly_the_whole_budget_the_target_is_met() {
        postedMinute(minuteAgo(2), 0, TRANSFERS, TRANSFERS * 95 / 100, TRANSFERS);

        var typical = typical(posting());

        assertThat(typical.share()).isEqualTo(0.95);
        assertThat(typical.budgetUsed()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a minute in a window declared in time takes all its 100 transfers out of the count, the 90 fast ones too")
    void a_minute_in_a_window_declared_in_time_takes_all_its_100_transfers_out_of_the_count_the_90_fast_ones_too() {
        var inWindow = minuteAgo(20);
        postedMinute(inWindow, 0, 100L, 90L, 95L);
        window(inWindow, inWindow.plusSeconds(60), inWindow.minus(NOTICE));
        postedMinute(minuteAgo(5), 0, 1000L, 1000L, 1000L);

        var posting = posting();

        assertThat(posting.judged()).isEqualTo(1000);
        assertThat(posting.planned()).isEqualTo(100);
        assertThat(typical(posting).within()).isEqualTo(1000);
        assertThat(typical(posting).share()).isEqualTo(1.0);
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
        assertThat(objectives.posting().planned()).isEqualTo(TRANSFERS);
        assertThat(objectives.posting().judged()).isZero();
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
        assertThat(objectives.posting().outside()).isEqualTo(2 * TRANSFERS);
        assertThat(objectives.posting().judged()).isEqualTo(TRANSFERS);
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
        assertThat(objectives.posting().outside()).isEqualTo(2 * TRANSFERS);
        assertThat(objectives.posting().judged()).isEqualTo(TRANSFERS);
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
        assertThat(objectives.posting().judged()).isEqualTo(TRANSFERS);
        assertThat(objectives.posting().outside()).isZero();
    }

    @Test
    @DisplayName("a crashed pod starting again once leaves the crash outside; starting twice makes every bad minute of the crash judged")
    void a_crashed_pod_starting_again_once_leaves_the_crash_outside_starting_twice_makes_every_bad_minute_of_the_crash_judged() {
        running(LEDGER);
        var once = minuteAgo(30);
        chaos(once.plusSeconds(10));
        started(LEDGER, once.plusSeconds(40));
        badForBoth(once);
        badForBoth(once.plusSeconds(60));
        var twice = minuteAgo(15);
        chaos(twice.plusSeconds(10));
        started(LEDGER, twice.plusSeconds(40));
        started(LEDGER, twice.plusSeconds(100));
        badForBoth(twice);
        badForBoth(twice.plusSeconds(60));

        var objectives = objectives();

        assertThat(objectives.slo().outsideMinutes()).isEqualTo(2);
        assertThat(objectives.slo().badMinutes()).isEqualTo(2);
        assertThat(objectives.posting().outside()).isEqualTo(2 * TRANSFERS);
        assertThat(objectives.posting().judged()).isEqualTo(2 * TRANSFERS);
    }

    @Test
    @DisplayName("a restart in place of a pod the crash did not crash makes the crash judged; so does any restart during a pulse")
    void a_restart_in_place_of_a_pod_the_crash_did_not_crash_makes_the_crash_judged_so_does_any_restart_during_a_pulse() {
        running(GATEWAY);
        running(LEDGER);
        var crashed = minuteAgo(30);
        chaos(crashed.plusSeconds(10));
        started(LEDGER, crashed.plusSeconds(40));
        started(GATEWAY, crashed.plusSeconds(50));
        badForBoth(crashed);
        var pulsed = minuteAgo(15);
        pulse(pulsed.plusSeconds(10), 2000, 30);
        started(LEDGER, pulsed.plusSeconds(20));
        badForBoth(pulsed);

        var objectives = objectives();

        assertThat(objectives.slo().badMinutes()).isEqualTo(2);
        assertThat(objectives.slo().outsideMinutes()).isZero();
        assertThat(objectives.posting().judged()).isEqualTo(2 * TRANSFERS);
        assertThat(objectives.posting().outside()).isZero();
    }

    @Test
    @DisplayName("a new pod's first start during a pulse is not a restart: the pulse stays outside")
    void a_new_pods_first_start_during_a_pulse_is_not_a_restart_the_pulse_stays_outside() {
        var pulsed = minuteAgo(15);
        pulse(pulsed.plusSeconds(10), 2000, 30);
        started(GATEWAY, pulsed.plusSeconds(20));
        badForBoth(pulsed);

        assertThat(objectives().slo().outsideMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("a restart 50 s after a crash's aftermath still makes the crash judged; one 70 s after it does not")
    void a_restart_50_s_after_a_crashs_aftermath_still_makes_the_crash_judged_one_70_s_after_it_does_not() {
        running(GATEWAY);
        var within = minuteAgo(30);
        chaos(within.plusSeconds(10));
        started(GATEWAY, within.plusSeconds(10 + 60 + 50));
        badForBoth(within);
        var past = minuteAgo(15);
        chaos(past.plusSeconds(10));
        started(GATEWAY, past.plusSeconds(10 + 60 + 70));
        badForBoth(past);

        var objectives = objectives();

        assertThat(objectives.slo().badMinutes()).isEqualTo(1);
        assertThat(objectives.slo().outsideMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("old starts are deleted, but each pod keeps its newest start before the cutoff, so a later start is still a restart")
    void old_starts_are_deleted_but_each_pod_keeps_its_newest_start_before_the_cutoff_so_a_later_start_is_still_a_restart() {
        var cutoff = clock.now().minus(Duration.ofDays(2));
        started(LEDGER, cutoff.minus(Duration.ofDays(2)));
        started(LEDGER, cutoff.minus(Duration.ofDays(1)));
        started(LEDGER, cutoff.plus(Duration.ofHours(1)));
        started(GATEWAY, cutoff.minus(Duration.ofDays(1)));

        assertThat(repository.deleteStartsBefore(cutoff)).isEqualTo(1);

        assertThat(jdbc.sql("SELECT instance, started_at FROM service_starts ORDER BY instance, started_at").query().listOfRows()).containsExactly(
            Map.of("instance", GATEWAY, "started_at", Timestamp.from(cutoff.minus(Duration.ofDays(1)))),
            Map.of("instance", LEDGER, "started_at", Timestamp.from(cutoff.minus(Duration.ofDays(1)))),
            Map.of("instance", LEDGER, "started_at", Timestamp.from(cutoff.plus(Duration.ofHours(1)))));
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

    @Test
    @DisplayName("posted transfers count from the hour the 30 days begin in, not the hour before; since is null when they cover the window")
    void posted_transfers_count_from_the_hour_the_30_days_begin_in_not_the_hour_before_since_is_null_when_they_cover_the_window() {
        var now = clock.now();
        var windowStart = now.minus(Duration.ofDays(30));
        postedHour(windowStart, 1000, 990);
        postedHour(windowStart.minus(Duration.ofHours(1)), 1000, 0);
        postedMinute(minuteAgo(2), 0, 1000L, 1000L, 1000L);

        var posting = availability.objectives(now).posting();

        assertThat(posting.judged()).isEqualTo(2000);
        assertThat(typical(posting).within()).isEqualTo(1990);
        assertThat(posting.since()).isNull();
    }

    @Test
    @DisplayName("posted transfers counted for a few days only tell since which hour")
    void posted_transfers_counted_for_a_few_days_only_tell_since_which_hour() {
        var now = clock.now();
        var firstHour = now.minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
        hour(now.minus(Duration.ofDays(10)), 60, 0);
        postedHour(firstHour, 1000, 1000);

        assertThat(availability.objectives(now).posting().since()).isEqualTo(firstHour);
    }

    private void postedHour(Instant within, long judged, long withinTypical) {
        jdbc.sql(
            """
            INSERT INTO availability_hours (hour, measured_minutes, bad_minutes, planned_minutes, total, errors, busy,
                                            posted_judged, posted_within_typical, posted_within_tail)
            VALUES (date_trunc('hour', CAST(:within AS timestamptz), 'UTC'), 60, 0, 0, :total, 0, 0, :judged, :withinTypical, :judged)
            """)
            .param("within", Timestamp.from(within))
            .param("total", 60 * REQUESTS)
            .param("judged", judged)
            .param("withinTypical", withinTypical)
            .update();
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
    @DisplayName("an hour that is over keeps its planned, outside and bad minutes and its posted transfers once its minutes are deleted")
    void an_hour_that_is_over_keeps_its_planned_outside_and_bad_minutes_and_its_posted_transfers_once_its_minutes_are_deleted() throws Exception {
        var lastHour = clock.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        badForBoth(lastHour.plusSeconds(60));
        postedMinute(lastHour.plusSeconds(120), 0, TRANSFERS, TRANSFERS / 2, TRANSFERS);
        badForBoth(lastHour.plusSeconds(600));
        window(lastHour.plusSeconds(600), lastHour.plusSeconds(660), lastHour.minus(Duration.ofDays(1)));
        badForBoth(lastHour.plusSeconds(1200));
        chaos(lastHour.plusSeconds(1200));
        postedMinute(lastHour.plusSeconds(1800), 0, null, null, null);
        generatorHandsOver(List.of());
        prometheus.status = 503;
        var before = objectives();

        availability.collect();

        assertThat(jdbc.sql(
            """
            SELECT measured_minutes, bad_minutes, planned_minutes, outside_minutes,
                   posted_judged, posted_within_typical, posted_within_tail, posted_planned, posted_outside
            FROM availability_hours
            """)
            .query().listOfRows())
            .containsExactly(mapOf(
                "measured_minutes", 5, "bad_minutes", 1, "planned_minutes", 1, "outside_minutes", 1,
                "posted_judged", 2 * TRANSFERS, "posted_within_typical", TRANSFERS, "posted_within_tail", TRANSFERS * 3 / 4 + TRANSFERS,
                "posted_planned", TRANSFERS, "posted_outside", TRANSFERS));
        assertThat(objectives()).isEqualTo(before);
        jdbc.sql("DELETE FROM availability_minutes WHERE minute < :hourEnd").param("hourEnd", Timestamp.from(lastHour.plus(Duration.ofHours(1)))).update();
        assertThat(objectives()).isEqualTo(before);
        assertThat(before.posting().judged()).isEqualTo(2 * TRANSFERS);
        assertThat(before.posting().since()).isEqualTo(lastHour);
        assertThat(before.slo().outsideMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("a period's load is the lowest and highest base of its minutes and the highest pulse that met it, none of the hours beside it")
    void a_periods_load_is_the_lowest_and_highest_base_of_its_minutes_and_the_highest_pulse_that_met_it_none_of_the_hours_beside_it()
        throws Exception {
        var hour = clock.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(2));
        var next = hour.plus(Duration.ofHours(1));
        generatorHandsOver(List.of(
            mapOf("minute", hour.minusSeconds(60), "baseTps", 200, "total", REQUESTS, "errors", 0, "busy", 0),
            mapOf("minute", hour, "baseTps", 300, "total", REQUESTS, "errors", 0, "busy", 0),
            mapOf("minute", hour.plusSeconds(1800), "baseTps", 800, "total", REQUESTS, "errors", 0, "busy", 0),
            mapOf("minute", next, "baseTps", 900, "total", REQUESTS, "errors", 0, "busy", 0)));
        availability.collect();
        pulse(hour.minusSeconds(10), 1400, 20);
        pulse(hour.plusSeconds(600), 1000, 30);
        pulse(next, 2000, 10);

        assertThat(repository.loadBetween(hour, next)).isEqualTo(new PeriodLoad(300, 800, 1400));
        assertThat(repository.loadBetween(next.plus(Duration.ofHours(1)), next.plus(Duration.ofHours(2)))).isEqualTo(new PeriodLoad(null, null, null));
    }

    private static LocalDate dayOf(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    @Test
    @DisplayName("the days are UTC days: a bad minute at 23:59 and one at 00:00 fall on two days")
    void the_days_are_utc_days_a_bad_minute_at_23_59_and_one_at_00_00_fall_on_two_days() {
        var midnight = clock.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(1));
        postedMinute(midnight.minusSeconds(60), REQUESTS / 2, 100L, 90L, 99L);
        postedMinute(midnight, REQUESTS / 2, 100L, 80L, 98L);

        var objectives = objectives();

        assertThat(objectives.slo().days()).containsExactly(
            new DayCount(dayOf(midnight.minusSeconds(60)), 1), new DayCount(dayOf(midnight), 1));
        assertThat(typical(objectives.posting()).days()).containsExactly(
            new DayCount(dayOf(midnight.minusSeconds(60)), 10), new DayCount(dayOf(midnight), 20));
        assertThat(tail(objectives.posting()).days()).containsExactly(
            new DayCount(dayOf(midnight.minusSeconds(60)), 1), new DayCount(dayOf(midnight), 2));
    }

    @Test
    @DisplayName("a day counts only what was judged: a bad minute in a window declared in time and the transfers of a pulse of 1400 are not in it")
    void a_day_counts_only_what_was_judged_a_planned_bad_minute_and_the_transfers_of_a_pulse_of_1400_are_not_in_it() {
        var day = clock.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(2)).plus(Duration.ofHours(10));
        badForBoth(day);
        window(day, day.plusSeconds(60), day.minus(NOTICE));
        pulse(day.plusSeconds(600), 1400, 30);
        postedMinute(day.plusSeconds(600), 0, 1000L, 500L, 600L);
        badForBoth(day.plusSeconds(1200));

        var objectives = objectives();

        assertThat(objectives.slo().days()).containsExactly(new DayCount(dayOf(day), 1));
        assertThat(typical(objectives.posting()).days()).containsExactly(new DayCount(dayOf(day), TRANSFERS / 2));
        assertThat(tail(objectives.posting()).days()).containsExactly(new DayCount(dayOf(day), TRANSFERS / 4));
    }

    @Test
    @DisplayName("at most 30 days are shown, today the last; the time to posting shows none before it was first counted")
    void at_most_30_days_are_shown_today_the_last_the_time_to_posting_shows_none_before_it_was_first_counted() {
        var now = clock.now();
        var today = now.truncatedTo(ChronoUnit.DAYS);
        hour(today.minus(Duration.ofDays(30)).plus(Duration.ofHours(12)), 60, 3);
        hour(today.minus(Duration.ofDays(29)).plus(Duration.ofHours(12)), 60, 2);
        hour(today.minus(Duration.ofDays(20)), 60, 1);
        postedHour(today.minus(Duration.ofDays(10)), 1000, 990);

        var objectives = availability.objectives(now);

        assertThat(objectives.slo().days()).extracting(DayCount::day).containsExactly(
            dayOf(today.minus(Duration.ofDays(29))), dayOf(today.minus(Duration.ofDays(20))), dayOf(today.minus(Duration.ofDays(10))));
        assertThat(objectives.slo().days()).extracting(DayCount::count).containsExactly(2L, 1L, 0L);
        assertThat(typical(objectives.posting()).days()).containsExactly(new DayCount(dayOf(today.minus(Duration.ofDays(10))), 10));
    }

    @Test
    @DisplayName("a bad minute of an hour not added up yet already shows in its day, beside the hours added up before it")
    void a_bad_minute_of_an_hour_not_added_up_yet_already_shows_in_its_day_beside_the_hours_added_up_before_it() {
        var hour = clock.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(3));
        hour(hour.minus(Duration.ofHours(1)), 60, 2);
        badMinute(hour.plusSeconds(300));

        var days = availability.objectives(hour.plusSeconds(600)).slo().days();

        var counted = days.stream().mapToLong(DayCount::count).sum();
        assertThat(counted).isEqualTo(3);
        assertThat(days.getLast().day()).isEqualTo(dayOf(hour));
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
