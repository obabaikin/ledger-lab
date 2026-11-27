package com.ledgerlab.generator.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.IntegrationTest;
import com.ledgerlab.generator.gateway.ResponseKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MinutesInPostgresTest extends IntegrationTest {

    /** Within the retention, or the context's own flush would drop it. */
    private static final Instant NOON = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(5));

    @Autowired JdbcMinuteStore store;
    @Autowired BaseSchedule schedule;

    @BeforeEach
    void noMinutes() {
        jdbcTemplate.update("DELETE FROM response_minutes");
    }

    private ResponseMinutes pod(Instant at) {
        return new ResponseMinutes(Clock.fixed(at, ZoneOffset.UTC), props, schedule, store);
    }

    private static void record(ResponseMinutes pod, ResponseKind kind, int times) {
        for (var i = 0; i < times; i++) {
            pod.record(kind);
        }
    }

    @Test
    void a_minute_two_pods_counted_holds_the_sum_of_both() {
        var old = pod(NOON.plusSeconds(20));
        var replacement = pod(NOON.plusSeconds(40));
        record(old, ResponseKind.ACCEPTED, 30);
        record(old, ResponseKind.NO_RESPONSE, 2);
        record(old, ResponseKind.BUSY, 1);
        old.flush();
        record(replacement, ResponseKind.ACCEPTED, 50);
        record(replacement, ResponseKind.SERVER_ERROR, 1);
        replacement.flush();
        record(old, ResponseKind.ACCEPTED, 3);
        old.flush();

        var reader = pod(NOON.plusSeconds(60).plus(props.minutes().settle()));
        assertThat(reader.completeAfter(null)).containsExactly(new MinuteResponses(NOON, schedule.baseAt(NOON), 87, 3, 1));
    }
}
