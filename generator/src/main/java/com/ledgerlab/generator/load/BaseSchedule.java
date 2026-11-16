package com.ledgerlab.generator.load;

import com.ledgerlab.generator.GeneratorProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The base load at any instant, a function of the time alone: a generator that starts again goes on with the same
 * curve. Without the profile the base is constant.
 */
@Component
public class BaseSchedule {

    private static final Logger log = LoggerFactory.getLogger(BaseSchedule.class);
    private static final long DATE_MIX = 0x9E3779B97F4A7C15L;

    private final int constantTps;
    private final GeneratorProperties.BaseProfile profile;
    private final Duration step;
    private final int largestStep;
    private volatile @Nullable Day lastDay = null;

    /** Base values of a day's slots: slot 0 starts when the night ends, the slot after the last one is the night. */
    private record Day(Instant start, Instant end, List<Integer> slots) {
        boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }

    public BaseSchedule(GeneratorProperties props) {
        var load = props.load();
        this.constantTps = load.tps();
        this.profile = load.profile();
        this.step = Duration.ofMinutes(profile.stepMinutes());
        this.largestStep = profile.steps().isEmpty() ? 0 : Collections.max(profile.steps());
        if (profile.enabled()) {
            log.info("The base load follows the profile: {}", profile);
        } else {
            log.info("The base load is constant: {} tps", constantTps);
        }
    }

    public int baseAt(Instant instant) {
        if (!profile.enabled()) {
            return constantTps;
        }
        var day = dayAt(instant);
        return day == null ? profile.nightTps() : day.slots().get(slotOf(day, instant));
    }

    /** When the base changes next after {@code instant}; null without the profile. */
    public @Nullable Instant nextChangeAfter(Instant instant) {
        if (!profile.enabled()) {
            return null;
        }
        var day = dayAt(instant);
        if (day != null) {
            return day.start().plus(step.multipliedBy(slotOf(day, instant) + 1L));
        }
        var today = startOf(localDate(instant));
        var nextStart = today.isAfter(instant) ? today : startOf(localDate(instant).plusDays(1));
        return nextStart.plus(step);
    }

    private int slotOf(Day day, Instant instant) {
        return (int) (Duration.between(day.start(), instant).toMillis() / step.toMillis());
    }

    private @Nullable Day dayAt(Instant instant) {
        var cached = lastDay;
        if (cached != null && cached.contains(instant)) {
            return cached;
        }
        var date = localDate(instant);
        for (var candidate : new LocalDate[] {date, date.minusDays(1)}) {
            var start = startOf(candidate);
            var end = endOf(candidate);
            if (!instant.isBefore(start) && instant.isBefore(end)) {
                var day = new Day(start, end, walk(candidate, start, end));
                lastDay = day;
                return day;
            }
        }
        return null;
    }

    private LocalDate localDate(Instant instant) {
        return instant.atZone(profile.timeZone()).toLocalDate();
    }

    private Instant startOf(LocalDate date) {
        return ZonedDateTime.of(date, profile.nightEnd(), profile.timeZone()).toInstant();
    }

    private Instant endOf(LocalDate date) {
        var nightDate = profile.nightStart().isAfter(profile.nightEnd()) ? date : date.plusDays(1);
        return ZonedDateTime.of(nightDate, profile.nightStart(), profile.timeZone()).toInstant();
    }

    private List<Integer> walk(LocalDate date, Instant start, Instant end) {
        var count = (int) (Duration.between(start, end).toMillis() / step.toMillis());
        var random = new SplittableRandom(profile.seed() ^ (date.toEpochDay() * DATE_MIX));
        var slots = new ArrayList<Integer>(count);
        slots.add(profile.nightTps());
        for (var slot = 1; slot < count; slot++) {
            slots.add(next(slots.getLast(), count - slot, random));
        }
        return List.copyOf(slots);
    }

    /**
     * One step of the walk. {@code stepsLeft} counts the boundaries still to come, the night's start included; every
     * value must be able to reach the night's value in that many of the largest steps down, so the evening descends
     * by itself and the night starts at its value without a jump.
     */
    private int next(int base, int stepsLeft, SplittableRandom random) {
        var inRange = new ArrayList<Integer>();
        var reachable = new ArrayList<Integer>();
        for (var size : profile.steps()) {
            for (var candidate : new int[] {base + size, base - size}) {
                if (candidate < profile.nightTps() || stepsDown(candidate) > stepsLeft) {
                    continue;
                }
                reachable.add(candidate);
                if (candidate >= profile.dayMinTps() && candidate <= profile.dayMaxTps()) {
                    inRange.add(candidate);
                }
            }
        }
        var choices = inRange.isEmpty() ? reachable : inRange;
        return choices.isEmpty() ? profile.nightTps() : choices.get(random.nextInt(choices.size()));
    }

    private int stepsDown(int base) {
        return Math.ceilDiv(base - profile.nightTps(), largestStep);
    }
}
