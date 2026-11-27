package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.chaos.ChaosService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"control.chaos.enabled=true", "control.chaos.request-timeout=300ms"})
class ChaosTest extends IntegrationTest {

    private static final String CRASH_PATH = "/actuator/crash";

    @Autowired MeterRegistry registry;

    private double presses() {
        return registry.get(ChaosService.METRIC).counter().count();
    }

    private ResponseEntity<Map<String, Object>> chaos(Object sessionId) {
        return post("/api/sessions/" + sessionId + "/chaos", null);
    }

    private Map<String, Object> session(UUID sessionId) {
        return get("/api/sessions/" + sessionId).getBody();
    }

    private UUID visitor() {
        return sessionOf(invite());
    }

    private List<Integer> crashCalls() {
        return ledgerInstances.stream().map(it -> it.callsTo("POST", CRASH_PATH).size()).toList();
    }

    private List<Map<String, Object>> events() {
        return jdbc.sql("SELECT at, array_to_string(instances, ',') AS instances FROM chaos_events ORDER BY at").query().listOfRows();
    }

    private void backdateChaos(Duration by) {
        jdbc.sql("UPDATE chaos_events SET at = at - CAST(:millis AS bigint) * interval '1 millisecond'").param("millis", by.toMillis()).update();
    }

    @Test
    void chaos_crashes_every_ledger_instance_and_lists_them() {
        var answer = chaos(visitor());

        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(answer.getBody().get("instances")).isEqualTo(List.of("ledger-0", "ledger-1", "ledger-2"));
        assertThat(answer.getBody().get("at")).isNotNull();
        assertThat(crashCalls()).containsExactly(1, 1, 1);
        assertThat(single(events()).get("instances")).isEqualTo("ledger-0,ledger-1,ledger-2");
    }

    @Test
    @DisplayName("a press that sent a crash counts on the chaos metric, an answered one and an unanswered one alike")
    void a_press_that_sent_a_crash_counts_on_the_chaos_metric() {
        var before = presses();
        chaos(visitor());
        backdateChaos(Duration.ofHours(1));
        ledgerInstances.forEach(it -> it.status = 503);

        chaos(visitor());

        assertThat(presses()).isEqualTo(before + 2);
    }

    @Test
    void instances_that_did_not_answer_are_left_out_of_the_answer() {
        ledgerInstances.get(1).status = 500;
        ledgerInstances.get(2).delay = Duration.ofMillis(800);

        var answer = chaos(visitor());

        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(answer.getBody().get("instances")).isEqualTo(List.of("ledger-0"));
        assertThat(single(events()).get("instances")).isEqualTo("ledger-0");
    }

    @Test
    @DisplayName("chaos answers 503 when no instance answered, and the interval is not used")
    void chaos_answers_503_when_no_instance_answered_and_the_interval_is_not_used() {
        ledgerInstances.forEach(it -> it.status = 503);
        var sessionId = visitor();

        var refused = chaos(sessionId);

        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(refused.getBody().get("error")).isEqualTo("ledger did not answer");
        assertThat(events()).isEmpty();
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(0);
        ledgerInstances.forEach(it -> it.status = 200);
        assertThat(chaos(sessionId).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void chaos_for_an_unknown_session_is_404() {
        var answer = chaos(UUID.randomUUID());

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(answer.getBody().get("error")).isEqualTo("session not found");
        assertThat(crashCalls()).containsExactly(0, 0, 0);
    }

    @Test
    @DisplayName("chaos needs no more than a session: the bank runs all the time")
    void chaos_needs_no_more_than_a_session_the_bank_runs_all_the_time() {
        assertThat(chaos(sessionOf(invite())).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("a session whose invite expired or was switched off cannot use chaos")
    void a_session_whose_invite_expired_or_was_switched_off_cannot_use_chaos() {
        var token = invite();
        var sessionId = sessionOf(token);
        jdbc.sql("UPDATE invite_tokens SET active = false WHERE token = :token").param("token", token).update();

        var answer = chaos(sessionId);

        assertThat(answer.getStatusCode().value()).isEqualTo(403);
        assertThat(answer.getBody().get("error")).isEqualTo("invite not valid");
        assertThat(crashCalls()).containsExactly(0, 0, 0);
    }

    @Test
    @DisplayName("a second chaos within the interval is 429 with Retry-After of the time left, whoever presses")
    void a_second_chaos_within_the_interval_is_429_with_Retry_After_of_the_time_left_whoever_presses() {
        var sessionId = visitor();
        assertThat(chaos(sessionId).getStatusCode().value()).isEqualTo(202);

        var again = chaos(visitor());
        assertThat(again.getStatusCode().value()).isEqualTo(429);
        assertThat(again.getBody().get("error")).isEqualTo("chaos used recently");
        assertThat(again.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("90");

        backdateChaos(Duration.ofMillis(30_500));
        assertThat(chaos(sessionId).getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(crashCalls()).containsExactly(1, 1, 1);
        assertThat(events()).hasSize(1);
    }

    @Test
    @DisplayName("chaos while another press is still crashing the ledger is 429 at once, to ask again after those calls' timeout")
    void chaos_while_another_press_is_still_crashing_the_ledger_is_429_at_once_to_ask_again_after_those_calls_timeout() throws Exception {
        var sessionId = visitor();

        var refused = whileLockHeld("control.chaos", Duration.ofSeconds(2), () -> chaos(sessionId));

        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(refused.getBody().get("error")).isEqualTo("chaos used recently");
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(crashCalls()).containsExactly(0, 0, 0);
        assertThat(events()).isEmpty();
    }

    @Test
    void of_two_simultaneous_chaos_requests_exactly_one_crashes_the_ledger() throws Exception {
        ledgerInstances.forEach(it -> it.delay = Duration.ofMillis(200));
        var sessions = List.of(visitor(), visitor());
        var ready = new CountDownLatch(sessions.size());

        var answers = new ArrayList<ResponseEntity<Map<String, Object>>>();
        try (var presses = Executors.newVirtualThreadPerTaskExecutor()) {
            var pressed = new ArrayList<Future<ResponseEntity<Map<String, Object>>>>();
            for (var sessionId : sessions) {
                Callable<ResponseEntity<Map<String, Object>>> press = () -> {
                    ready.countDown();
                    ready.await();
                    return chaos(sessionId);
                };
                pressed.add(presses.submit(press));
            }
            for (var future : pressed) {
                answers.add(future.get());
            }
        }

        assertThat(answers.stream().map(it -> it.getStatusCode().value()).toList()).containsExactlyInAnyOrder(202, 429);
        var refused = single(answers.stream().filter(it -> it.getStatusCode().value() == 429).toList());
        assertThat(Integer.parseInt(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1, 90);
        assertThat(crashCalls()).containsExactly(1, 1, 1);
        assertThat(events()).hasSize(1);
    }

    @Test
    void the_session_counts_down_to_the_next_chaos_and_reads_0_once_the_interval_has_passed() {
        var sessionId = visitor();
        assertThat(session(sessionId).get("chaosAvailable")).isEqualTo(true);
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(0);

        chaos(sessionId);
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(90);
        backdateChaos(Duration.ofMillis(45_200));
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(45);
        backdateChaos(Duration.ofSeconds(45));
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(0);
        assertThat(chaos(sessionId).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("chaosReadyInSeconds equals Retry-After of a refused chaos")
    void chaosReadyInSeconds_equals_Retry_After_of_a_refused_chaos() {
        var sessionId = visitor();
        chaos(sessionId);
        backdateChaos(Duration.ofMillis(12_345));

        var retryAfter = chaos(sessionId).getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

        assertThat(session(sessionId).get("chaosReadyInSeconds").toString()).isEqualTo(retryAfter);
    }
}
