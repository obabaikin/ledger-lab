package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunsPropertiesTest {
    @Test
    void the_default_TPS_must_be_one_of_the_options() {
        assertThatThrownBy(() -> runs(List.of(50, 100, 300), 500))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("default-tps 500");
    }

    @Test
    void a_default_TPS_among_the_options_is_accepted() {
        assertThatCode(() -> runs(List.of(50, 100, 300), 100)).doesNotThrowAnyException();
    }

    private ControlProperties.Runs runs(List<Integer> tpsOptions, int defaultTps) {
        return new ControlProperties.Runs(
            3,
            10,
            Duration.ofMinutes(5),
            tpsOptions,
            defaultTps,
            List.of(Duration.ofSeconds(30)),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(60),
            Duration.ofMinutes(3),
            new ControlProperties.Accounts(100, 1_000_000, 2, 5_000_000),
            500,
            4,
            Duration.ofMillis(200));
    }
}
