package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.api.ControlErrors;
import com.ledgerlab.control.entry.TurnstileClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
        "control.turnstile.secret=" + TurnstileTest.SECRET,
        "control.turnstile.site-key=" + TurnstileTest.SITE_KEY,
        "control.turnstile.request-timeout=500ms",
    })
class TurnstileTest extends IntegrationTest {

    static final String SECRET = "turnstile-test-secret";
    static final String SITE_KEY = "turnstile-test-site-key";

    private void verdict(boolean success) {
        turnstile.answer("POST", TurnstileClient.SITEVERIFY_PATH, 200, mapOf("success", success, "error-codes", List.of()));
    }

    private void nothingRecorded() {
        assertThat(jdbc.sql("SELECT count(*) FROM invite_requests").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a passed check lets the request through, with the secret and the widget's answer")
    void a_passed_check_lets_the_request_through_with_the_secret_and_the_widgets_answer() {
        verdict(true);

        assertThat(requestLink("visitor@example.org", "widget-answer").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        var call = objectMapper.readTree(single(turnstile.callsTo("POST", TurnstileClient.SITEVERIFY_PATH)).body());
        assertThat(call.get("secret").asString()).isEqualTo(SECRET);
        assertThat(call.get("response").asString()).isEqualTo("widget-answer");
    }

    @Test
    void a_failed_or_missing_check_is_403_and_nothing_is_recorded() {
        verdict(false);

        Arrays.asList("widget-answer", null).forEach(answer -> {
            var response = requestLink("visitor@example.org", answer);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(objectMapper.readTree(response.getBody()).get("error").asString()).isEqualTo(ControlErrors.VERIFICATION_FAILED);
        });
        nothingRecorded();
    }

    @Test
    void a_verifier_that_fails_or_does_not_answer_is_503_busy_and_nothing_is_recorded() {
        turnstile.status = 500;
        var failed = requestLink("visitor@example.org", "widget-answer");
        turnstile.reset();
        turnstile.answer("POST", TurnstileClient.SITEVERIFY_PATH, 200, mapOf("success", true), Duration.ofSeconds(1));
        var silent = requestLink("visitor@example.org", "widget-answer");

        List.of(failed, silent).forEach(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(objectMapper.readTree(response.getBody()).get("error").asString()).isEqualTo(ControlErrors.BUSY);
        });
        nothingRecorded();
    }

    @Test
    @DisplayName("the page gets the widget's site key")
    void the_page_gets_the_widgets_site_key() {
        assertThat(get("/api/site").getBody().get("turnstileSiteKey")).isEqualTo(SITE_KEY);
    }
}
