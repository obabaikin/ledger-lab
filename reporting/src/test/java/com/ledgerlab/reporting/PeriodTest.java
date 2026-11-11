package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.reporting.domain.Period;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PeriodTest {

    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void an_instant_belongs_to_the_period_that_starts_at_or_before_it_and_ends_after_it() {
        var tenOClock = Instant.parse("2026-11-10T10:00:00Z");
        var period = Period.containing(tenOClock, HOUR);

        assertThat(period).isEqualTo(new Period(tenOClock, Instant.parse("2026-11-10T11:00:00Z")));
        assertThat(Period.containing(Instant.parse("2026-11-10T10:59:59.999999Z"), HOUR).start()).isEqualTo(tenOClock);
        assertThat(Period.containing(Instant.parse("2026-11-10T09:59:59.999999Z"), HOUR).end()).isEqualTo(tenOClock);
        assertThat(period.contains(tenOClock)).isTrue();
        assertThat(period.contains(Instant.parse("2026-11-10T10:59:59.999999Z"))).isTrue();
        assertThat(period.contains(Instant.parse("2026-11-10T11:00:00Z"))).isFalse();
        assertThat(period.contains(Instant.parse("2026-11-10T09:59:59.999999Z"))).isFalse();
    }

    @Test
    void periods_of_ten_minutes_lie_on_the_same_grid_in_every_hour() {
        var period = Period.containing(Instant.parse("2026-11-10T10:17:03Z"), Duration.ofMinutes(10));

        assertThat(period.start()).isEqualTo(Instant.parse("2026-11-10T10:10:00Z"));
        assertThat(period.next().start()).isEqualTo(Instant.parse("2026-11-10T10:20:00Z"));
        assertThat(period.previous().start()).isEqualTo(Instant.parse("2026-11-10T10:00:00Z"));
    }

    @Test
    void only_a_start_on_the_grid_names_a_period() {
        assertThat(Period.startsOnGrid(Instant.parse("2026-11-10T10:00:00Z"), HOUR)).isTrue();
        assertThat(Period.startsOnGrid(Instant.parse("2026-11-10T10:00:01Z"), HOUR)).isFalse();
        assertThat(Period.startsOnGrid(Instant.parse("2026-11-10T10:30:00Z"), HOUR)).isFalse();
    }
}
