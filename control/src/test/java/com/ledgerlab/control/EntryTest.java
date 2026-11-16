package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.api.ControlErrors;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EntryTest extends IntegrationTest {

    private Long visits(String token) {
        return jdbc.sql("SELECT count FROM invite_visits WHERE token = :token").param("token", token).query(Long.class).optional().orElse(null);
    }

    @Test
    void an_invite_opens_a_session_and_counts_the_visit() {
        var token = invite();

        var response = enter(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var sessionId = (String) response.getBody().get("sessionId");
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/sessions/" + sessionId);
        assertThat(visits(token)).isEqualTo(1);
    }

    @Test
    void every_opening_of_the_link_is_a_new_session_and_one_more_visit() {
        var token = invite();

        var first = sessionOf(token);
        var second = sessionOf(token);

        assertThat(first).isNotEqualTo(second);
        assertThat(visits(token)).isEqualTo(2);
    }

    @Test
    void an_unknown_token_gets_403_and_leaves_no_visit() {
        var response = enter("no-such-invite");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.INVITE_NOT_VALID);
        assertThat(jdbc.sql("SELECT count(*) FROM invite_visits").query(Long.class).single()).isZero();
    }

    @Test
    void a_missing_or_blank_token_gets_403() {
        assertThat(enter(null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(enter(" ").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an expired or inactive invite gets 403 and no session, but its visit is counted")
    void an_expired_or_inactive_invite_gets_403_and_no_session_but_its_visit_is_counted() {
        var expired = invite(Duration.ofMinutes(-1), true);
        var inactive = invite(Duration.ofDays(1), false);

        List.of(expired, inactive).forEach(token -> {
            var response = enter(token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.INVITE_NOT_VALID);
            assertThat(visits(token)).isEqualTo(1);
        });
        assertThat(jdbc.sql("SELECT count(*) FROM sessions").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a session shows whether its invite holds and whether chaos may be used")
    void a_session_shows_whether_its_invite_holds_and_whether_chaos_may_be_used() {
        var sessionId = sessionOf(invite());

        var view = get("/api/sessions/" + sessionId).getBody();

        assertThat(view).isEqualTo(Map.of(
            "sessionId", sessionId.toString(), "inviteValid", true, "chaosAvailable", false, "chaosReadyInSeconds", 0,
            "loadTpsOptions", List.of(900, 1000, 1100, 1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900, 2000),
            "defaultLoadTps", 1000, "loadDurationOptions", List.of(10, 20, 30), "defaultLoadSeconds", 10, "loadReadyInSeconds", 0));
    }

    @Test
    @DisplayName("a session of an invite switched off later still opens, as not valid")
    void a_session_of_an_invite_switched_off_later_still_opens_as_not_valid() {
        var token = invite();
        var sessionId = sessionOf(token);
        jdbc.sql("UPDATE invite_tokens SET active = false WHERE token = :token").param("token", token).update();

        var view = get("/api/sessions/" + sessionId);

        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody().get("inviteValid")).isEqualTo(false);
    }

    @Test
    void an_unknown_session_is_404() {
        var response = get("/api/sessions/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.SESSION_NOT_FOUND);
    }
}
