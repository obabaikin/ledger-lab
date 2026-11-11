package com.ledgerlab.generator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The clients' view of the bank's availability, minute by minute. Kept in memory: a restart of the generator loses
 * the minutes nobody has read yet, and a minute without requests has no entry at all.
 */
@Component
public class ResponseMinutes {

    private static final long SECONDS_PER_MINUTE = Duration.ofMinutes(1).toSeconds();

    private final Clock clock;
    private final Duration retention;
    private final ConcurrentSkipListMap<Long, Counts> byMinute = new ConcurrentSkipListMap<>();

    private static final class Counts {
        private final LongAdder total = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder busy = new LongAdder();
    }

    public ResponseMinutes(Clock clock, GeneratorProperties props) {
        this.clock = clock;
        this.retention = props.load().minutesRetention();
    }

    public void record(ResponseKind kind) {
        if (!kind.isAboutTheBank()) {
            return;
        }
        var counts = byMinute.computeIfAbsent(minuteOf(clock.instant()), minute -> new Counts());
        counts.total.increment();
        if (kind.isError()) {
            counts.errors.increment();
        } else if (kind == ResponseKind.BUSY) {
            counts.busy.increment();
        }
    }

    /** Minutes that are over, oldest first; the running one is left out, as it still changes. */
    public List<MinuteResponses> completeAfter(@Nullable Instant after) {
        var now = clock.instant();
        byMinute.headMap(minuteOf(now.minus(retention))).clear();
        var running = minuteOf(now);
        var from = after == null ? Long.MIN_VALUE : minuteOf(after);
        if (from >= running) {
            return List.of();
        }
        return byMinute.subMap(from, false, running, false).entrySet().stream()
            .map(entry -> new MinuteResponses(
                Instant.ofEpochSecond(entry.getKey() * SECONDS_PER_MINUTE),
                entry.getValue().total.sum(), entry.getValue().errors.sum(), entry.getValue().busy.sum()))
            .toList();
    }

    private static long minuteOf(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), SECONDS_PER_MINUTE);
    }
}
