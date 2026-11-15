package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ControlPropertiesTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final Duration RETENTION = Duration.ofHours(4);
    private static final Duration COUNT_TIMEOUT = Duration.ofSeconds(30);

    private static ControlProperties.Periods periods(Duration length, Duration closeDelay, Duration settleDelay, Duration verdictDeadline) {
        return new ControlProperties.Periods(length, closeDelay, settleDelay, FIVE_MINUTES, FIVE_MINUTES, verdictDeadline, RETENTION, COUNT_TIMEOUT);
    }

    @Test
    @DisplayName("the closing times in their order start: the close, the count, the deadline, and the next period's close")
    void the_closing_times_in_their_order_start_the_close_the_count_the_deadline_and_the_next_periods_close() {
        assertThatCode(() -> periods(HOUR, MINUTE, Duration.ofMinutes(10), Duration.ofMinutes(40))).doesNotThrowAnyException();
        assertThatCode(() -> periods(Duration.ofMinutes(10), MINUTE, Duration.ofMinutes(3), Duration.ofMinutes(10))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("closing times out of their order stop the start")
    void closing_times_out_of_their_order_stop_the_start() {
        assertThatThrownBy(() -> periods(HOUR, MINUTE, MINUTE, Duration.ofMinutes(40))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods(HOUR, MINUTE, Duration.ofMinutes(40), Duration.ofMinutes(40))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods(HOUR, MINUTE, Duration.ofMinutes(10), HOUR.plus(MINUTE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods(Duration.ofMinutes(10), MINUTE, Duration.ofMinutes(10), Duration.ofMinutes(40)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("waits that are not positive stop the start")
    void waits_that_are_not_positive_stop_the_start() {
        assertThatThrownBy(() -> new ControlProperties.Periods(HOUR, MINUTE, Duration.ofMinutes(10), Duration.ZERO, FIVE_MINUTES, Duration.ofMinutes(40), RETENTION, COUNT_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlProperties.Periods(HOUR, MINUTE, Duration.ofMinutes(10), FIVE_MINUTES, Duration.ZERO, Duration.ofMinutes(40), RETENTION, COUNT_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlProperties.Periods(HOUR, MINUTE, Duration.ofMinutes(10), FIVE_MINUTES, FIVE_MINUTES, Duration.ofMinutes(40), RETENTION, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a default range of the charts that is not one of the ranges stops the start")
    void a_default_range_of_the_charts_that_is_not_one_of_the_ranges_stops_the_start() {
        var ranges = List.of(new ControlProperties.Range(Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofSeconds(15)));

        assertThatCode(() -> new ControlProperties.Series(ranges, Duration.ofMinutes(5), Duration.ofSeconds(15))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ControlProperties.Series(ranges, HOUR, Duration.ofSeconds(15))).isInstanceOf(IllegalArgumentException.class);
    }
}
