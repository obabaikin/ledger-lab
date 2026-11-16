package com.ledgerlab.generator.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.GeneratorProperties;
import com.ledgerlab.generator.TestData;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BaseScheduleTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Duration STEP = Duration.ofMinutes(5);
    private static final int NIGHT = 200;
    private static final int DAY_MIN = 300;
    private static final int DAY_MAX = 800;
    private static final int LARGEST_STEP = 200;
    private static final LocalDate SUMMER_TIME_STARTS = LocalDate.of(2026, 3, 29);
    private static final LocalDate SUMMER_TIME_ENDS = LocalDate.of(2026, 10, 25);
    private static final int SEEDS = 400;

    private static BaseSchedule schedule(long seed) {
        return new BaseSchedule(TestData.settings("http://localhost:1").baseProfile(TestData.dayProfile(seed)).build());
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), BERLIN).toInstant();
    }

    /** The base of every step of the day, from 06:00 to the next midnight, both included. */
    private static List<Integer> day(BaseSchedule schedule, LocalDate date) {
        var bases = new ArrayList<Integer>();
        for (var t = at(date, 6, 0); !t.isAfter(at(date.plusDays(1), 0, 0)); t = t.plus(STEP)) {
            bases.add(schedule.baseAt(t));
        }
        return bases;
    }

    @Test
    @DisplayName("from midnight to 06:00 in Berlin the base is the night's, on the days the clocks change as well")
    void from_midnight_to_six_in_berlin_the_base_is_the_nights_on_the_days_the_clocks_change_as_well() {
        var schedule = schedule(7);
        for (var date : List.of(LocalDate.of(2026, 11, 16), SUMMER_TIME_STARTS, SUMMER_TIME_ENDS)) {
            for (var t = at(date, 0, 0); t.isBefore(at(date, 6, 0)); t = t.plus(Duration.ofMinutes(1))) {
                assertThat(schedule.baseAt(t)).as("%s", t).isEqualTo(NIGHT);
            }
        }
    }

    @Test
    @DisplayName("the day always has 216 steps of 5 minutes, also when the clocks change")
    void the_day_always_has_216_steps_of_5_minutes_also_when_the_clocks_change() {
        var schedule = schedule(7);
        for (var date : List.of(LocalDate.of(2026, 11, 16), SUMMER_TIME_STARTS.minusDays(1), SUMMER_TIME_STARTS, SUMMER_TIME_ENDS.minusDays(1), SUMMER_TIME_ENDS)) {
            var changes = 0;
            var t = at(date, 6, 0);
            for (var next = schedule.nextChangeAfter(t); next != null && !next.isAfter(at(date.plusDays(1), 0, 0)); next = schedule.nextChangeAfter(t)) {
                changes++;
                t = next;
            }
            assertThat(changes).as("%s", date).isEqualTo(216);
        }
    }

    @Test
    @DisplayName("over 400 seeds, after the first step the day stays between 300 and 800, and every step is 100 or 200 up or down")
    void over_400_seeds_after_the_first_step_the_day_stays_between_300_and_800_and_every_step_is_100_or_200_up_or_down() {
        for (var seed = 0; seed < SEEDS; seed++) {
            var bases = day(schedule(seed), LocalDate.of(2026, 11, 16).plusDays(seed % 30));
            for (var i = 1; i < bases.size(); i++) {
                assertThat(Math.abs(bases.get(i) - bases.get(i - 1))).as("seed %d step %d", seed, i).isIn(100, 200);
            }
            // The last step is the one to the night, below the day's floor.
            assertThat(bases.subList(1, bases.size() - 1)).as("seed %d", seed).allSatisfy(base -> assertThat(base).isBetween(DAY_MIN, DAY_MAX));
        }
    }

    @Test
    @DisplayName("over 400 seeds the base can always get down to the night's in the steps left, and is the night's at midnight")
    void over_400_seeds_the_base_can_always_get_down_to_the_nights_in_the_steps_left_and_is_the_nights_at_midnight() {
        for (var seed = 0; seed < SEEDS; seed++) {
            var bases = day(schedule(seed), LocalDate.of(2026, 11, 16).plusDays(seed % 30));
            var stepsLeft = bases.size() - 1;
            for (var i = 0; i < bases.size(); i++, stepsLeft--) {
                assertThat(bases.get(i) - NIGHT).as("seed %d step %d", seed, i).isLessThanOrEqualTo(LARGEST_STEP * stepsLeft);
            }
            assertThat(bases.getLast()).as("seed %d", seed).isEqualTo(NIGHT);
        }
    }

    @Test
    @DisplayName("the first step of the day, at 06:05, goes up")
    void the_first_step_of_the_day_at_06_05_goes_up() {
        for (var seed = 0; seed < SEEDS; seed++) {
            var date = LocalDate.of(2026, 11, 16);
            var schedule = schedule(seed);
            assertThat(schedule.baseAt(at(date, 6, 0))).isEqualTo(NIGHT);
            assertThat(schedule.baseAt(at(date, 6, 5))).as("seed %d", seed).isGreaterThan(NIGHT);
        }
    }

    @Test
    @DisplayName("the same seed and time give the same base: a new schedule goes on with the curve; another seed walks another way")
    void the_same_seed_and_time_give_the_same_base_a_new_schedule_goes_on_with_the_curve_another_seed_walks_another_way() {
        var date = LocalDate.of(2026, 11, 16);
        var first = day(schedule(42), date);
        assertThat(day(schedule(42), date)).isEqualTo(first);

        var restarted = schedule(42);
        assertThat(restarted.baseAt(at(date, 15, 2))).isEqualTo(first.get(108));

        var curves = new HashSet<List<Integer>>();
        for (var seed = 0; seed < 10; seed++) {
            curves.add(day(schedule(seed), date));
        }
        assertThat(curves).hasSizeGreaterThan(1);
        assertThat(day(schedule(42), date.plusDays(1))).isNotEqualTo(first);
    }

    @Test
    @DisplayName("the base changes only on the 5-minute boundaries; the next change is the next boundary, at night 06:05")
    void the_base_changes_only_on_the_5_minute_boundaries_the_next_change_is_the_next_boundary_at_night_06_05() {
        var schedule = schedule(3);
        var date = LocalDate.of(2026, 11, 16);
        var slotStart = at(date, 9, 10);
        var base = schedule.baseAt(slotStart);
        for (var t = slotStart; t.isBefore(slotStart.plus(STEP)); t = t.plusSeconds(10)) {
            assertThat(schedule.baseAt(t)).isEqualTo(base);
            assertThat(schedule.nextChangeAfter(t)).isEqualTo(at(date, 9, 15));
        }
        assertThat(schedule.nextChangeAfter(at(date, 2, 30))).isEqualTo(at(date, 6, 5));
        assertThat(schedule.nextChangeAfter(at(date, 6, 0))).isEqualTo(at(date, 6, 5));
        assertThat(schedule.nextChangeAfter(at(date, 23, 57))).isEqualTo(at(date.plusDays(1), 0, 0));
        assertThat(schedule.nextChangeAfter(at(date.plusDays(1), 0, 0))).isEqualTo(at(date.plusDays(1), 6, 5));
    }

    @Test
    @DisplayName("a night moved across midnight works the same: night 23:00 to 01:00, the day in between")
    void a_night_moved_across_midnight_works_the_same() {
        var profile = TestData.dayProfile(5);
        var moved = new GeneratorProperties.BaseProfile(
            true, 5, profile.nightTps(), profile.dayMinTps(), profile.dayMaxTps(), profile.steps(), 5, LocalTime.of(23, 0), LocalTime.of(1, 0), BERLIN);
        var schedule = new BaseSchedule(TestData.settings("http://localhost:1").baseProfile(moved).build());
        var date = LocalDate.of(2026, 11, 16);

        assertThat(schedule.baseAt(at(date, 23, 30))).isEqualTo(NIGHT);
        assertThat(schedule.baseAt(at(date.plusDays(1), 0, 30))).isEqualTo(NIGHT);
        assertThat(schedule.nextChangeAfter(at(date, 23, 30))).isEqualTo(at(date.plusDays(1), 1, 5));
        assertThat(schedule.baseAt(at(date, 22, 55))).isBetween(DAY_MIN, NIGHT + LARGEST_STEP);
    }

    @Test
    @DisplayName("without the profile the base is tps all the time and has no next change")
    void without_the_profile_the_base_is_tps_all_the_time_and_has_no_next_change() {
        var schedule = new BaseSchedule(TestData.settings("http://localhost:1").tps(400).build());
        var date = LocalDate.of(2026, 11, 16);

        assertThat(schedule.baseAt(at(date, 3, 0))).isEqualTo(400);
        assertThat(schedule.baseAt(at(date, 15, 0))).isEqualTo(400);
        assertThat(schedule.nextChangeAfter(at(date, 15, 0))).isNull();
    }
}
