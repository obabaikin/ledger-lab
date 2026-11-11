package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LoadApiTest extends IntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private ResponseEntity<Map<String, Object>> pulse(Map<String, Object> body) {
        return http.exchange("/internal/load/pulse", HttpMethod.POST, new HttpEntity<>(body), JSON_OBJECT);
    }

    @Test
    void the_load_reports_its_pace_and_counts() {
        var status = http.getForObject("/internal/load", LoadStatus.class);

        assertThat(status.baseTps()).isEqualTo(props.load().tps());
        assertThat(status.startedAt()).as("the test profile keeps the load off").isNull();
        assertThat(status.requested()).isZero();
    }

    @Test
    @DisplayName("a pulse above the ceiling is refused with 400 and a code")
    void a_pulse_above_the_ceiling_is_refused_with_400_and_a_code() {
        var aboveTheCeiling = pulse(Map.of("tps", props.load().maxPulseTps() + 1, "seconds", 30));
        var withoutAPace = pulse(Map.of("seconds", 30));
        var withoutALength = pulse(Map.of("tps", 500));

        assertThat(aboveTheCeiling.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(aboveTheCeiling.getBody()).containsEntry("error", GeneratorErrors.INVALID_PULSE);
        assertThat(withoutAPace.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withoutAPace.getBody()).containsEntry("error", GeneratorErrors.INVALID_PULSE);
        assertThat(withoutALength.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withoutALength.getBody()).containsEntry("error", GeneratorErrors.INVALID_PULSE);
    }

    @Test
    void a_pulse_at_the_ceiling_is_taken_and_shows_in_the_status() {
        var taken = pulse(Map.of("tps", props.load().maxPulseTps(), "seconds", 1));

        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taken.getBody()).containsEntry("targetTps", props.load().maxPulseTps());
        assertThat(taken.getBody().get("pulse")).isNotNull();
    }

    @Test
    void the_answers_by_minute_are_served_as_a_list_that_is_empty_before_the_first_full_minute() {
        var all = http.exchange("/internal/load/minutes", HttpMethod.GET, null, JSON_OBJECT);
        var afterAnHourAgo = http.exchange("/internal/load/minutes?after=" + Instant.now().minusSeconds(3_600), HttpMethod.GET, null, JSON_OBJECT);
        var afterATimeToCome = http.exchange("/internal/load/minutes?after=" + Instant.now().plusSeconds(3_600), HttpMethod.GET, null, JSON_OBJECT);

        for (var page : List.of(all, afterAnHourAgo, afterATimeToCome)) {
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page.getBody()).containsEntry("minutes", List.of());
        }
    }
}
