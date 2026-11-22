package com.ledgerlab.generator.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.GeneratorProperties;
import com.ledgerlab.generator.TestData;
import com.ledgerlab.generator.gateway.ResponseKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ResponseMinutesTest {

    private static final Instant NOON = Instant.parse("2026-11-11T12:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(NOON);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private static final GeneratorProperties SETTINGS = TestData.settings("http://localhost:1").build();
    private static final Duration SETTLE = SETTINGS.minutes().settle();
    private final MemoryMinuteStore store = new MemoryMinuteStore();
    private final ResponseMinutes minutes = new ResponseMinutes(clock, SETTINGS, new BaseSchedule(SETTINGS), store);

    private void record(ResponseKind kind, int times) {
        for (var i = 0; i < times; i++) {
            minutes.record(kind);
        }
        minutes.flush();
    }

    /** The moment a minute that started then is given out. */
    private static Instant settled(Instant minute) {
        return minute.plusSeconds(60).plus(SETTLE);
    }

    @Test
    @DisplayName("a 5xx, a stopping instance and no answer are errors; busy is an answer, counted apart")
    void a_5xx_a_stopping_instance_and_no_answer_are_errors_busy_is_an_answer_counted_apart() {
        record(ResponseKind.ACCEPTED, 90);
        record(ResponseKind.INVALID, 1);
        record(ResponseKind.BUSY, 4);
        record(ResponseKind.SERVER_ERROR, 1);
        record(ResponseKind.STOPPING, 1);
        record(ResponseKind.NOT_CONNECTED, 1);
        record(ResponseKind.NO_RESPONSE, 2);
        now.set(settled(NOON));

        assertThat(minutes.completeAfter(null)).containsExactly(new MinuteResponses(NOON, 100, 100, 5, 4));
    }

    @Test
    void a_send_the_generator_cut_short_or_failed_itself_says_nothing_about_the_bank() {
        record(ResponseKind.ACCEPTED, 1);
        record(ResponseKind.CANCELLED, 3);
        record(ResponseKind.SEND_ERROR, 2);
        now.set(settled(NOON));

        assertThat(minutes.completeAfter(null)).containsExactly(new MinuteResponses(NOON, 100, 1, 0, 0));
    }

    @Test
    void a_minute_is_given_out_only_once_the_settle_time_after_its_end_has_passed() {
        record(ResponseKind.ACCEPTED, 1);
        now.set(settled(NOON).minusMillis(1));
        assertThat(minutes.completeAfter(null)).isEmpty();

        now.set(settled(NOON));
        assertThat(minutes.completeAfter(null)).hasSize(1);
    }

    @Test
    void counts_a_failed_write_left_behind_are_added_by_the_next_flush_once() {
        var failing = new AtomicBoolean(true);
        var flaky = new MemoryMinuteStore() {
            @Override
            public void add(List<MinuteCounts> counts) {
                if (failing.get()) {
                    throw new DataAccessResourceFailureException("database down");
                }
                super.add(counts);
            }
        };
        var minutes = new ResponseMinutes(clock, SETTINGS, new BaseSchedule(SETTINGS), flaky);
        minutes.record(ResponseKind.ACCEPTED);
        minutes.flush();
        minutes.record(ResponseKind.ACCEPTED);
        failing.set(false);
        minutes.flush();
        minutes.flush();
        now.set(settled(NOON));

        assertThat(minutes.completeAfter(null)).containsExactly(new MinuteResponses(NOON, 100, 2, 0, 0));
    }

    @Test
    void a_minute_without_requests_has_no_entry() {
        record(ResponseKind.ACCEPTED, 1);
        now.set(NOON.plusSeconds(120));
        record(ResponseKind.ACCEPTED, 2);
        now.set(settled(NOON.plusSeconds(120)));

        assertThat(minutes.completeAfter(null)).extracting(MinuteResponses::minute).containsExactly(NOON, NOON.plusSeconds(120));
    }

    @Test
    void only_minutes_after_the_given_instant_are_returned() {
        record(ResponseKind.ACCEPTED, 1);
        now.set(NOON.plusSeconds(60));
        record(ResponseKind.ACCEPTED, 1);
        now.set(settled(NOON.plusSeconds(60)));

        assertThat(minutes.completeAfter(NOON)).extracting(MinuteResponses::minute).containsExactly(NOON.plusSeconds(60));
    }

    @Test
    void asked_for_the_minutes_after_one_that_has_not_come_the_answer_is_empty() {
        record(ResponseKind.ACCEPTED, 1);
        now.set(settled(NOON));

        assertThat(minutes.completeAfter(NOON.plusSeconds(60))).isEmpty();
        assertThat(minutes.completeAfter(NOON.plusSeconds(3_600))).isEmpty();
    }

    @Test
    void minutes_older_than_the_retention_are_dropped() {
        record(ResponseKind.ACCEPTED, 1);
        now.set(settled(NOON.plus(SETTINGS.load().minutesRetention())));
        minutes.flush();

        assertThat(minutes.completeAfter(null)).isEmpty();
        assertThat(store.between(NOON, NOON)).isEmpty();
    }
}
