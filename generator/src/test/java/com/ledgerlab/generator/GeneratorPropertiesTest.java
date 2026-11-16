package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GeneratorPropertiesTest {

    @Test
    void a_work_mem_below_postgres_minimum_stops_the_start() {
        var belowMinimum = GeneratorProperties.Reconciliation.MIN_WORK_MEM.toBytes() - 1;

        assertThatThrownBy(() -> new GeneratorProperties.Reconciliation(Duration.ofSeconds(60), DataSize.ofBytes(belowMinimum), 20, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("work-mem");
    }

    private static GeneratorProperties.BaseProfile profile(int night, int dayMin, int dayMax, List<Integer> steps, int stepMinutes, LocalTime nightEnd) {
        return new GeneratorProperties.BaseProfile(true, 1, night, dayMin, dayMax, steps, stepMinutes, LocalTime.MIDNIGHT, nightEnd, ZoneId.of("Europe/Berlin"));
    }

    @Test
    void a_base_profile_out_of_order_without_steps_off_its_multiples_or_off_the_hour_stops_the_start() {
        var six = LocalTime.of(6, 0);
        assertThatCode(() -> profile(200, 300, 800, List.of(100, 200), 5, six)).doesNotThrowAnyException();

        assertThatThrownBy(() -> profile(400, 300, 800, List.of(100, 200), 5, six)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(200, 300, 800, List.of(), 5, six)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(200, 300, 800, List.of(100, 150), 5, six)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(200, 350, 800, List.of(100, 200), 5, six)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(200, 300, 800, List.of(100, 200), 7, six)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(200, 300, 800, List.of(100, 200), 5, LocalTime.of(6, 3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(200, 300, 800, List.of(100, 200), 5, LocalTime.MIDNIGHT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_base_profile_that_is_off_is_not_checked() {
        assertThatCode(() -> new GeneratorProperties.BaseProfile(false, 0, 0, 0, 0, List.of(), 0, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ZoneId.of("UTC")))
            .doesNotThrowAnyException();
    }
}
