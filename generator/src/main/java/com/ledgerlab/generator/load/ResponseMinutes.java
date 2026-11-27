package com.ledgerlab.generator.load;

import com.ledgerlab.generator.GeneratorProperties;
import com.ledgerlab.generator.gateway.ResponseKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clients' view of the bank's availability, minute by minute. Counted in memory and added to the database every
 * {@code generator.minutes.flush-interval} and when the load stops, so a minute two instances shared holds both their
 * counts and a stop loses none. A minute without requests has no entry at all.
 */
@Component
public class ResponseMinutes {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final long SECONDS_PER_MINUTE = MINUTE.toSeconds();

    private static final Logger log = LoggerFactory.getLogger(ResponseMinutes.class);

    private final Clock clock;
    private final BaseSchedule schedule;
    private final MinuteStore store;
    private final Duration retention;
    private final Duration settle;
    /** Counts not added to the store yet. */
    private final ConcurrentSkipListMap<Long, Counts> pending = new ConcurrentSkipListMap<>();
    /** Touched in {@link #flush()} only, which runs one at a time. */
    private boolean failing = false;
    private long droppedBefore = Long.MIN_VALUE;

    private static final class Counts {
        private final LongAdder total = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder busy = new LongAdder();
    }

    public ResponseMinutes(Clock clock, GeneratorProperties props, BaseSchedule schedule, MinuteStore store) {
        this.clock = clock;
        this.schedule = schedule;
        this.store = store;
        this.retention = props.load().minutesRetention();
        this.settle = props.minutes().settle();
    }

    public void record(ResponseKind kind) {
        if (!kind.isAboutTheBank()) {
            return;
        }
        var counts = pending.computeIfAbsent(minuteOf(clock.instant()), minute -> new Counts());
        counts.total.increment();
        if (kind.isError()) {
            counts.errors.increment();
        } else if (kind == ResponseKind.BUSY) {
            counts.busy.increment();
        }
    }

    @Scheduled(fixedDelayString = "${generator.minutes.flush-interval}")
    void flushOnSchedule() {
        flush();
    }

    /**
     * Adds what was counted since the last flush. A count is taken off only once the store has it: a failed write
     * leaves it for the next flush.
     */
    public synchronized void flush() {
        var running = minuteOf(clock.instant());
        var taken = new ArrayList<Taken>();
        pending.forEach((minute, counts) -> {
            var total = counts.total.sum();
            if (total > 0) {
                taken.add(new Taken(counts, new MinuteCounts(instantOf(minute), total, counts.errors.sum(), counts.busy.sum())));
            }
        });
        try {
            if (!taken.isEmpty()) {
                store.add(taken.stream().map(Taken::minute).toList());
            }
            dropExpired();
        } catch (DataAccessException e) {
            if (!failing) {
                failing = true;
                log.warn("The minutes could not be written; they are kept for the next attempt", e);
            }
            return;
        }
        if (failing) {
            failing = false;
            log.info("The minutes are written again");
        }
        for (var it : taken) {
            it.counts().total.add(-it.minute().total());
            it.counts().errors.add(-it.minute().errors());
            it.counts().busy.add(-it.minute().busy());
        }
        // Answers are counted to the minute they end in: one that ended before the last minute has long been counted.
        pending.headMap(running - 1).entrySet().removeIf(entry -> entry.getValue().total.sum() == 0);
    }

    private record Taken(Counts counts, MinuteCounts minute) {
    }

    private void dropExpired() {
        var oldest = minuteOf(clock.instant().minus(retention));
        if (oldest > droppedBefore) {
            store.dropBefore(instantOf(oldest));
            droppedBefore = oldest;
        }
    }

    /**
     * Minutes that are over, oldest first. A minute is given out only {@code generator.minutes.settle} after its end,
     * so the last counts of an instance that stopped in it are in.
     */
    public List<MinuteResponses> completeAfter(@Nullable Instant after) {
        var now = clock.instant();
        var last = minuteOf(now.minus(settle).minus(MINUTE));
        var oldest = minuteOf(now.minus(retention));
        var first = after == null ? oldest : Math.max(oldest, minuteOf(after) + 1);
        if (first > last) {
            return List.of();
        }
        return store.between(instantOf(first), instantOf(last)).stream()
            .map(minute -> new MinuteResponses(minute.minute(), schedule.baseAt(minute.minute()), minute.total(), minute.errors(), minute.busy()))
            .toList();
    }

    private static long minuteOf(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), SECONDS_PER_MINUTE);
    }

    private static Instant instantOf(long minute) {
        return Instant.ofEpochSecond(minute * SECONDS_PER_MINUTE);
    }
}
