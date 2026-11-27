package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.http.ServiceHttp;
import com.ledgerlab.control.load.GeneratorDidNotAnswerException;
import com.ledgerlab.control.load.LoadAbovePromise;
import com.ledgerlab.control.load.LoadRepository;
import com.ledgerlab.control.load.LoadService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

class LoadTest extends IntegrationTest {

    private static final String PULSE_PATH = "/internal/load/pulse";

    @Autowired Cleanup cleanup;
    @Autowired GeneratorClient generatorClient;
    @Autowired LoadService loadService;
    @Autowired LoadAbovePromise loadAbovePromise;

    @BeforeEach
    void generatorTakesPulses() {
        generator.answer("POST", PULSE_PATH, 200, Fixtures.load());
    }

    private ResponseEntity<Map<String, Object>> load(Object sessionId, Object tps, Object seconds) {
        return post("/api/sessions/" + sessionId + "/load", mapOf("tps", tps, "seconds", seconds));
    }

    private Map<String, Object> session(UUID sessionId) {
        return get("/api/sessions/" + sessionId).getBody();
    }

    private UUID visitor() {
        return sessionOf(invite());
    }

    private List<StubService.Call> pulseCalls() {
        return generator.callsTo("POST", PULSE_PATH);
    }

    private List<Map<String, Object>> pulses() {
        return jdbc.sql("SELECT at, tps, seconds, until FROM load_pulses ORDER BY at").query().listOfRows();
    }

    private void backdatePulses(Duration by) {
        jdbc.sql("UPDATE load_pulses SET at = at - CAST(:millis AS bigint) * interval '1 millisecond', until = until - CAST(:millis AS bigint) * interval '1 millisecond'")
            .param("millis", by.toMillis())
            .update();
    }

    private static String retryAfter(ResponseEntity<Map<String, Object>> answer) {
        return answer.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    }

    @Test
    @DisplayName("a press raises the load: 202 with its speed and end, and the generator gets exactly the speed and length pressed")
    void a_press_raises_the_load_202_with_its_speed_and_end_and_the_generator_gets_exactly_the_speed_and_length_pressed() {
        var answer = load(visitor(), 1500, 20);

        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(answer.getBody().get("tps")).isEqualTo(1500);
        var pulse = single(pulses());
        var until = ((Timestamp) pulse.get("until")).toInstant();
        assertThat(Instant.parse((String) answer.getBody().get("until"))).isEqualTo(until);
        assertThat(Duration.between(((Timestamp) pulse.get("at")).toInstant(), until)).isEqualTo(Duration.ofSeconds(20));
        assertThat(pulse.get("tps")).isEqualTo(1500);
        assertThat(pulse.get("seconds")).isEqualTo(20);
        JsonNode sent = objectMapper.readTree(single(pulseCalls()).body());
        assertThat(sent).isEqualTo(objectMapper.valueToTree(mapOf("tps", 1500, "seconds", 20)));
    }

    @Test
    @DisplayName("a speed or length not among the options, a missing field or an unreadable body is 400 invalid-load, and the generator is not called")
    void a_speed_or_length_not_among_the_options_a_missing_field_or_an_unreadable_body_is_400_invalid_load_and_the_generator_is_not_called() {
        var sessionId = visitor();
        var refused = new ArrayList<ResponseEntity<Map<String, Object>>>();
        refused.add(load(sessionId, 1550, 10));
        refused.add(load(sessionId, 800, 10));
        refused.add(load(sessionId, 2100, 10));
        refused.add(load(sessionId, 1000, 15));
        refused.add(load(sessionId, null, 10));
        refused.add(load(sessionId, 1000, null));
        refused.add(load(sessionId, "fast", 10));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        refused.add(postEntity("/api/sessions/" + sessionId + "/load", new HttpEntity<>("{not json", headers)));

        refused.forEach(answer -> {
            assertThat(answer.getStatusCode().value()).isEqualTo(400);
            assertThat(answer.getBody().get("error")).isEqualTo("invalid-load");
        });
        assertThat(pulseCalls()).isEmpty();
        assertThat(pulses()).isEmpty();
        assertThat(load(sessionId, 2000, 30).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("the lowest speed of a pulse is 900, above the day's highest base")
    void the_lowest_speed_of_a_pulse_is_900_above_the_days_highest_base() {
        assertThat(load(visitor(), 900, 10).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("a speed or length sent as a fraction, an exponent or a string is 400 invalid-load, not rounded to an option")
    void a_speed_or_length_sent_as_a_fraction_an_exponent_or_a_string_is_400_invalid_load_not_rounded_to_an_option() {
        var sessionId = visitor();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var bodies = List.of(
            "{\"tps\": 1000.5, \"seconds\": 10}",
            "{\"tps\": 1000, \"seconds\": 10.9}",
            "{\"tps\": 1e3, \"seconds\": 10}",
            "{\"tps\": \"1000\", \"seconds\": 10}",
            "{\"tps\": 1000, \"seconds\": \"10\"}",
            "{\"tps\": 4294968296, \"seconds\": 10}");

        bodies.forEach(body -> {
            var answer = postEntity("/api/sessions/" + sessionId + "/load", new HttpEntity<>(body, headers));
            assertThat(answer.getStatusCode().value()).as(body).isEqualTo(400);
            assertThat(answer.getBody().get("error")).as(body).isEqualTo("invalid-load");
        });
        assertThat(pulseCalls()).isEmpty();
    }

    @Test
    @DisplayName("an unknown session is 404, a session whose invite was switched off is 403, and the generator is not called")
    void an_unknown_session_is_404_a_session_whose_invite_was_switched_off_is_403_and_the_generator_is_not_called() {
        var unknown = load(UUID.randomUUID(), 1000, 10);
        var token = invite();
        var sessionId = sessionOf(token);
        jdbc.sql("UPDATE invite_tokens SET active = false WHERE token = :token").param("token", token).update();
        var switchedOff = load(sessionId, 1000, 10);

        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody().get("error")).isEqualTo("session not found");
        assertThat(switchedOff.getStatusCode().value()).isEqualTo(403);
        assertThat(switchedOff.getBody().get("error")).isEqualTo("invite not valid");
        assertThat(pulseCalls()).isEmpty();
        assertThat(pulses()).isEmpty();
    }

    @Test
    @DisplayName("a press from another session while a pulse runs is 429 load-running, Retry-After the time left plus the pause")
    void a_press_from_another_session_while_a_pulse_runs_is_429_load_running_Retry_After_the_time_left_plus_the_pause() {
        assertThat(load(visitor(), 1000, 10).getStatusCode().value()).isEqualTo(202);
        backdatePulses(Duration.ofMillis(3_500));

        var again = load(visitor(), 2000, 30);

        assertThat(again.getStatusCode().value()).isEqualTo(429);
        assertThat(again.getBody().get("error")).isEqualTo("load-running");
        assertThat(retryAfter(again)).isEqualTo("67");
        assertThat(pulseCalls()).hasSize(1);
        assertThat(pulses()).hasSize(1);
    }

    @Test
    @DisplayName("a press in the pause after a pulse is 429 load-used-recently; once the pause is over it is 202")
    void a_press_in_the_pause_after_a_pulse_is_429_load_used_recently_once_the_pause_is_over_it_is_202() {
        var sessionId = visitor();
        load(sessionId, 1000, 10);
        backdatePulses(Duration.ofMillis(14_500));

        var inPause = load(sessionId, 1000, 10);

        assertThat(inPause.getStatusCode().value()).isEqualTo(429);
        assertThat(inPause.getBody().get("error")).isEqualTo("load-used-recently");
        assertThat(retryAfter(inPause)).isEqualTo("56");
        backdatePulses(Duration.ofMillis(55_600));
        assertThat(load(sessionId, 1000, 10).getStatusCode().value()).isEqualTo(202);
        assertThat(pulseCalls()).hasSize(2);
    }

    @Test
    @DisplayName("a press while another press is still calling the generator is 429 load-running at once, to ask again after that call's timeout")
    void a_press_while_another_press_is_still_calling_the_generator_is_429_load_running_at_once_to_ask_again_after_that_calls_timeout() throws Exception {
        var sessionId = visitor();

        var refused = whileLockHeld("control.load", Duration.ofSeconds(2), () -> load(sessionId, 1000, 10));

        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(refused.getBody().get("error")).isEqualTo("load-running");
        assertThat(retryAfter(refused)).isEqualTo(Long.toString(Math.ceilDiv(props.services().requestTimeout().toMillis(), 1000)));
        assertThat(pulseCalls()).isEmpty();
        assertThat(pulses()).isEmpty();
    }

    @Test
    void of_two_simultaneous_presses_exactly_one_raises_the_load_and_the_generator_is_called_once() throws Exception {
        generator.answer("POST", PULSE_PATH, 200, Fixtures.load(), Duration.ofMillis(200));
        var sessions = List.of(visitor(), visitor());
        var ready = new CountDownLatch(sessions.size());

        var answers = new ArrayList<ResponseEntity<Map<String, Object>>>();
        try (var presses = Executors.newVirtualThreadPerTaskExecutor()) {
            var pressed = new ArrayList<Future<ResponseEntity<Map<String, Object>>>>();
            for (var sessionId : sessions) {
                Callable<ResponseEntity<Map<String, Object>>> press = () -> {
                    ready.countDown();
                    ready.await();
                    return load(sessionId, 1000, 10);
                };
                pressed.add(presses.submit(press));
            }
            for (var future : pressed) {
                answers.add(future.get());
            }
        }

        assertThat(answers.stream().map(it -> it.getStatusCode().value()).toList()).containsExactlyInAnyOrder(202, 429);
        var refused = single(answers.stream().filter(it -> it.getStatusCode().value() == 429).toList());
        assertThat(refused.getBody().get("error")).isEqualTo("load-running");
        assertThat(pulseCalls()).hasSize(1);
        assertThat(pulses()).hasSize(1);
    }

    @Test
    @DisplayName("a pulse the generator refuses is 503 and not kept, so the next press raises the load")
    void a_pulse_the_generator_refuses_is_503_and_not_kept_so_the_next_press_raises_the_load() {
        generator.answer("POST", PULSE_PATH, 500, mapOf("error", "boom"));
        var sessionId = visitor();

        var refused = load(sessionId, 1000, 10);

        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(refused.getBody().get("error")).isEqualTo("generator-did-not-answer");
        assertThat(pulses()).isEmpty();
        assertThat(session(sessionId).get("loadReadyInSeconds")).isEqualTo(0);
        generator.answer("POST", PULSE_PATH, 200, Fixtures.load());
        assertThat(load(sessionId, 1000, 10).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("a pulse the generator gives no answer to is 503 but kept, as it may run: the next press is 429 load-running")
    void a_pulse_the_generator_gives_no_answer_to_is_503_but_kept_as_it_may_run_the_next_press_is_429_load_running() {
        generator.answer("POST", PULSE_PATH, 200, Fixtures.load(), Duration.ofMillis(800));
        var sessionId = visitor();

        var unanswered = load(sessionId, 1000, 10);

        assertThat(unanswered.getStatusCode().value()).isEqualTo(503);
        assertThat(unanswered.getBody().get("error")).isEqualTo("generator-did-not-answer");
        assertThat(pulses()).hasSize(1);
        generator.answer("POST", PULSE_PATH, 200, Fixtures.load());
        var next = load(sessionId, 1000, 10);
        assertThat(next.getStatusCode().value()).isEqualTo(429);
        assertThat(next.getBody().get("error")).isEqualTo("load-running");
    }

    @Test
    @DisplayName("an answer of the generator it cannot read is 503 but kept, as the pulse may run")
    void an_answer_of_the_generator_it_cannot_read_is_503_but_kept_as_the_pulse_may_run() {
        generator.answer("POST", PULSE_PATH, 200, "not json");

        var sessionId = visitor();

        var unreadable = load(sessionId, 1000, 10);

        assertThat(unreadable.getStatusCode().value()).isEqualTo(503);
        assertThat(unreadable.getBody().get("error")).isEqualTo("generator-did-not-answer");
        assertThat(pulses()).hasSize(1);
        assertThat(load(sessionId, 1000, 10).getBody().get("error")).isEqualTo("load-running");
    }

    @Test
    @DisplayName("a press interrupted while it calls the generator keeps the pulse as unanswered and leaves the thread interrupted")
    void a_press_interrupted_while_it_calls_the_generator_keeps_the_pulse_as_unanswered_and_leaves_the_thread_interrupted() throws Exception {
        generator.answer("POST", PULSE_PATH, 200, Fixtures.load(), Duration.ofSeconds(2));
        var sessionId = visitor();
        var pressing = new CompletableFuture<Thread>();
        var stillInterrupted = new CompletableFuture<Boolean>();

        var press = new Thread(() -> {
            pressing.complete(Thread.currentThread());
            assertThatThrownBy(() -> loadService.raise(sessionId, 1000, 10)).isInstanceOf(GeneratorDidNotAnswerException.class);
            stillInterrupted.complete(Thread.currentThread().isInterrupted());
        });
        press.start();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(() -> !pulseCalls().isEmpty());
        pressing.get().interrupt();
        press.join(Duration.ofSeconds(5).toMillis());

        assertThat(stillInterrupted.get()).isTrue();
        assertThat(pulses()).hasSize(1);
        assertThat(load(sessionId, 1000, 10).getBody().get("error")).isEqualTo("load-running");
    }

    @Test
    @DisplayName("a pulse that never reached the generator is 503 and not kept, so the next press raises the load")
    void a_pulse_that_never_reached_the_generator_is_503_and_not_kept_so_the_next_press_raises_the_load() {
        var reachable = ReflectionTestUtils.getField(generatorClient, "http");
        var sessionId = visitor();
        try {
            ReflectionTestUtils.setField(generatorClient, "http", new ServiceHttp("http://localhost:1", Duration.ofMillis(500), objectMapper));

            var notConnected = load(sessionId, 1000, 10);

            assertThat(notConnected.getStatusCode().value()).isEqualTo(503);
            assertThat(notConnected.getBody().get("error")).isEqualTo("generator-did-not-answer");
            assertThat(pulses()).isEmpty();
        } finally {
            ReflectionTestUtils.setField(generatorClient, "http", reachable);
        }
        assertThat(load(sessionId, 1000, 10).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("the session lists the options and defaults, and its loadReadyInSeconds equals Retry-After of a refused press")
    void the_session_lists_the_options_and_defaults_and_its_loadReadyInSeconds_equals_Retry_After_of_a_refused_press() {
        var sessionId = visitor();
        var fresh = session(sessionId);
        assertThat(fresh.get("loadTpsOptions")).isEqualTo(List.of(
            900, 1000, 1100, 1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900, 2000));
        assertThat(fresh.get("defaultLoadTps")).isEqualTo(1000);
        assertThat(fresh.get("loadDurationOptions")).isEqualTo(List.of(10, 20, 30));
        assertThat(fresh.get("defaultLoadSeconds")).isEqualTo(10);
        assertThat(fresh.get("loadReadyInSeconds")).isEqualTo(0);

        load(sessionId, 1000, 10);
        backdatePulses(Duration.ofMillis(12_345));
        var refused = load(sessionId, 1000, 10);

        assertThat(session(sessionId).get("loadReadyInSeconds").toString()).isEqualTo(retryAfter(refused));
    }

    @Test
    void pulses_past_the_retention_are_purged_with_the_chaos_events() {
        load(visitor(), 1000, 10);
        backdatePulses(props.cleanup().retention().plusMinutes(1));
        load(visitor(), 1000, 10);

        cleanup.purge();

        assertThat(pulses()).hasSize(1);
    }

    private double abovePromise() {
        loadAbovePromise.read();
        return meters.get(LoadAbovePromise.METRIC).gauge().value();
    }

    @Test
    void without_a_pulse_the_load_above_the_promise_is_told_as_0() {
        assertThat(abovePromise()).isZero();
    }

    @Test
    void a_pulse_at_the_promised_speed_is_not_told_as_above_the_promise() {
        load(visitor(), props.outside().guaranteedTps(), 30);

        assertThat(abovePromise()).isZero();
    }

    @Test
    void a_pulse_above_the_promised_speed_is_told_as_above_the_promise_from_its_start_until_its_aftermath_is_over() {
        var seconds = 10;
        var aftermath = props.outside().loadAftermath();
        var margin = Duration.ofSeconds(5);

        load(visitor(), props.outside().guaranteedTps() + props.load().tpsStep(), seconds);
        assertThat(abovePromise()).isOne();

        backdatePulses(Duration.ofSeconds(seconds).plus(aftermath).minus(margin));
        assertThat(abovePromise()).isOne();

        backdatePulses(margin.multipliedBy(2));
        assertThat(abovePromise()).isZero();
    }

    @Test
    void a_pulse_above_the_promised_speed_the_generator_may_have_taken_is_told_as_above_the_promise() {
        generator.answer("POST", PULSE_PATH, 200, "not json");

        load(visitor(), props.outside().guaranteedTps() + props.load().tpsStep(), 10);

        assertThat(pulses()).hasSize(1);
        assertThat(abovePromise()).isOne();
    }

    @Test
    void until_the_pulses_are_first_read_the_load_above_the_promise_is_told_as_unknown_not_as_within_it() {
        var pulses = mock(LoadRepository.class);
        when(pulses.aboveNow(anyInt(), any())).thenReturn(false);
        var registry = new SimpleMeterRegistry();
        var told = new LoadAbovePromise(pulses, props, registry);
        var gauge = registry.get(LoadAbovePromise.METRIC).gauge();

        assertThat(gauge.value()).isNaN();

        told.read();
        assertThat(gauge.value()).isZero();
    }

    @Test
    void while_the_pulses_cannot_be_read_the_load_above_the_promise_is_told_as_unknown_not_as_the_last_value_read() {
        var failure = new DataAccessResourceFailureException("the database is away");
        var pulses = mock(LoadRepository.class);
        when(pulses.aboveNow(anyInt(), any())).thenReturn(false).thenThrow(failure).thenReturn(true);
        var registry = new SimpleMeterRegistry();
        var told = new LoadAbovePromise(pulses, props, registry);
        var gauge = registry.get(LoadAbovePromise.METRIC).gauge();

        told.read();
        assertThat(gauge.value()).isZero();

        assertThatThrownBy(told::read).isSameAs(failure);
        assertThat(gauge.value()).isNaN();

        told.read();
        assertThat(gauge.value()).isOne();
    }
}
