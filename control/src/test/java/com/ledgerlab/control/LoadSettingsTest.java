package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Control binds its load settings at start; settings it cannot offer stop it there. */
class LoadSettingsTest {

    private static Map<String, String> settings() {
        var settings = new HashMap<String, String>();
        settings.put("control.load.min-tps", "400");
        settings.put("control.load.max-tps", "2000");
        settings.put("control.load.tps-step", "100");
        settings.put("control.load.default-tps", "1000");
        settings.put("control.load.duration-options", "10s, 20s, 30s");
        settings.put("control.load.default-duration", "10s");
        settings.put("control.load.min-interval", "60s");
        return settings;
    }

    private static ControlProperties.Load bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("control.load", ControlProperties.Load.class);
    }

    @Test
    void the_options_run_from_the_lowest_speed_to_the_highest_in_steps() {
        var load = bind(settings());

        assertThat(load.tpsOptions()).first().isEqualTo(400);
        assertThat(load.tpsOptions()).last().isEqualTo(2000);
        assertThat(load.tpsOptions()).hasSize(17);
        assertThat(load.secondsOptions()).isEqualTo(List.of(10, 20, 30));
        assertThat(load.defaultDuration()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("control does not start with a default speed or length outside the options, or a range that is no whole number of steps")
    void control_does_not_start_with_a_default_speed_or_length_outside_the_options_or_a_range_that_is_no_whole_number_of_steps() {
        var broken = List.of(
            Map.of("control.load.default-tps", "1050"),
            Map.of("control.load.default-tps", "2100"),
            Map.of("control.load.default-duration", "15s"),
            Map.of("control.load.max-tps", "2050"),
            Map.of("control.load.tps-step", "0"),
            Map.of("control.load.duration-options", "10s, 1500ms"));

        broken.forEach(change -> {
            var settings = settings();
            settings.putAll(change);
            assertThatThrownBy(() -> bind(settings)).as(change.toString())
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }
}
