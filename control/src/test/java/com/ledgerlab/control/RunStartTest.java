package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.api.ControlErrors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class RunStartTest extends IntegrationTest {

    private long runsOf(String token) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE token = :token").param("token", token).query(Long.class).single();
    }

    private <T> List<T> concurrently(int times, Callable<T> call) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(times)) {
            var futures = new ArrayList<Future<T>>();
            for (var i = 0; i < times; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return call.call();
                }));
            }
            gate.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private long count(List<HttpStatusCode> statuses, HttpStatus status) {
        return statuses.stream().filter(status::equals).count();
    }

    @Test
    void a_start_joins_the_queue_with_its_position_and_location() {
        var token = invite();
        var sessionId = sessionOf(token);
        start(sessionOf(token));

        var response = start(sessionId, UUID.randomUUID(), 500, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var run = response.getBody();
        assertThat(run.get("state")).isEqualTo("QUEUED");
        assertThat(run.get("position")).isEqualTo(2);
        assertThat(run.get("tps")).isEqualTo(500);
        assertThat(run.get("durationSeconds")).isEqualTo(20);
        assertThat(run.get("admittedAt")).isNull();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/sessions/" + sessionId + "/runs/" + run.get("runId"));
        assertThat(getRun(sessionId, run.get("runId")).getBody()).isEqualTo(run);
    }

    @Test
    void tps_and_duration_outside_the_lists_are_refused_with_the_allowed_values() {
        var sessionId = sessionOf(invite());

        var tps = startWithTps(sessionId, 200);
        var duration = start(sessionId, UUID.randomUUID(), DEFAULT_TPS, 60);
        var missing = startWithTps(sessionId, null);

        assertThat(tps.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tps.getBody().get("error")).isEqualTo("tps must be one of [100, 500, 1000, 2000]");
        assertThat(duration.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(duration.getBody().get("error")).isEqualTo("durationSeconds must be one of [10, 20, 30]");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> startWithoutRequestId(Object sessionId) {
        return post("/api/sessions/" + sessionId + "/runs", mapOf("tps", 100, "durationSeconds", 10));
    }

    private ResponseEntity<Map<String, Object>> postRaw(String path, String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return postEntity(path, new HttpEntity<>(body, headers));
    }

    @Test
    void a_start_without_a_requestId_is_refused() {
        var response = startWithoutRequestId(sessionOf(invite()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.REQUEST_ID_REQUIRED);
    }

    @Test
    @DisplayName("a start in an unknown session is 404, even without a requestId")
    void a_start_in_an_unknown_session_is_404_even_without_a_requestId() {
        var response = startWithoutRequestId(UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.SESSION_NOT_FOUND);
    }

    @Test
    void an_unreadable_body_is_400_with_an_error_body() {
        var sessionId = sessionOf(invite());

        List.of("/api/sessions", "/api/sessions/" + sessionId + "/runs").forEach(path -> {
            var response = postRaw(path, "{not json");
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("error")).as(path).isEqualTo(ControlErrors.INVALID_REQUEST_BODY);
        });
    }

    @Test
    @DisplayName("an id that is not a UUID is 404, as an unknown one")
    void an_id_that_is_not_a_UUID_is_404_as_an_unknown_one() {
        var sessionId = sessionOf(invite());

        var session = get("/api/sessions/not-a-uuid");
        var runs = start("not-a-uuid");
        var run = getRun(sessionId, "not-a-uuid");

        assertThat(Stream.of(session, runs, run).map(ResponseEntity::getStatusCode).toList()).containsOnly(HttpStatus.NOT_FOUND);
        assertThat(session.getBody().get("error")).isEqualTo(ControlErrors.SESSION_NOT_FOUND);
        assertThat(runs.getBody().get("error")).isEqualTo(ControlErrors.SESSION_NOT_FOUND);
        assertThat(run.getBody().get("error")).isEqualTo(ControlErrors.RUN_NOT_FOUND);
    }

    @Test
    void a_repeat_with_the_same_requestId_returns_the_same_run_and_spends_no_quota() {
        var token = invite(1);
        var sessionId = sessionOf(token);
        var requestId = UUID.randomUUID();

        var first = start(sessionId, requestId);
        var repeat = start(sessionId, requestId);

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(repeat.getBody().get("runId")).isEqualTo(first.getBody().get("runId"));
        assertThat(runsOf(token)).isEqualTo(1);
    }

    @Test
    void a_repeat_with_a_different_body_is_422() {
        var sessionId = sessionOf(invite());
        var requestId = UUID.randomUUID();
        start(sessionId, requestId);

        var response = start(sessionId, requestId, 500, DEFAULT_DURATION_SECONDS);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.REQUEST_ID_CONFLICT);
    }

    @Test
    void a_repeat_of_a_final_run_answers_200() {
        var sessionId = sessionOf(invite());
        var requestId = UUID.randomUUID();
        var runId = start(sessionId, requestId).getBody().get("runId");
        setRun(runId, "EXPIRED");

        var repeat = start(sessionId, requestId);

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeat.getBody().get("state")).isEqualTo("EXPIRED");
    }

    @Test
    void the_same_requestId_in_another_session_is_another_run() {
        var token = invite();
        var requestId = UUID.randomUUID();

        var first = start(sessionOf(token), requestId);
        var second = start(sessionOf(token), requestId);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().get("runId")).isNotEqualTo(first.getBody().get("runId"));
    }

    @Test
    @DisplayName("the quota counts every start of the invite in the last hour, from any session and in any state, and says when to retry")
    void the_quota_counts_every_start_of_the_invite_in_the_last_hour_from_any_session_and_in_any_state_and_says_when_to_retry() {
        var token = invite(2);
        var expired = start(sessionOf(token)).getBody().get("runId");
        setRun(expired, "EXPIRED", Duration.ofMinutes(50));
        start(sessionOf(token));

        var response = start(sessionOf(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.RUN_QUOTA_EXCEEDED);
        assertThat(Long.parseLong(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(595L, 600L);
    }

    @Test
    @DisplayName("with a quota lowered below the starts made, Retry-After waits until fewer than the quota remain")
    void with_a_quota_lowered_below_the_starts_made_Retry_After_waits_until_fewer_than_the_quota_remain() {
        var token = invite(5);
        List.of(50L, 40L, 30L, 20L).forEach(it -> setRun(start(sessionOf(token)).getBody().get("runId"), Duration.ofMinutes(it)));
        jdbc.sql("UPDATE invite_tokens SET quota = 2 WHERE token = :token").param("token", token).update();

        var response = start(sessionOf(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1795L, 1800L);
    }

    @Test
    void starts_older_than_an_hour_do_not_count_against_the_quota() {
        var token = invite(1);
        setRun(start(sessionOf(token)).getBody().get("runId"), Duration.ofMinutes(61));

        assertThat(start(sessionOf(token)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a used-up quota is reported before a full queue")
    void a_used_up_quota_is_reported_before_a_full_queue() {
        var token = invite(props.runs().maxQueued());
        for (var i = 0; i < props.runs().maxQueued(); i++) {
            start(sessionOf(token));
        }

        var response = start(sessionOf(token));

        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.RUN_QUOTA_EXCEEDED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void a_repeat_gets_its_run_even_after_the_invite_was_turned_off_and_the_tps_options_changed() {
        var token = invite();
        var sessionId = sessionOf(token);
        var requestId = UUID.randomUUID();
        var runId = start(sessionId, requestId).getBody().get("runId");
        jdbc.sql("UPDATE invite_tokens SET active = false WHERE token = :token").param("token", token).update();
        jdbc.sql("UPDATE runs SET tps = 200 WHERE run_id = CAST(:runId AS uuid)").param("runId", runId.toString()).update();

        assertThat(start(sessionId, requestId, 200, DEFAULT_DURATION_SECONDS).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a full queue refuses the next start without Retry-After")
    void a_full_queue_refuses_the_next_start_without_Retry_After() {
        var token = invite();
        for (var i = 0; i < props.runs().maxQueued(); i++) {
            assertThat(start(sessionOf(token)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        var response = start(sessionOf(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.RUN_QUEUE_FULL);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void runs_that_left_the_queue_do_not_fill_it() {
        var token = invite();
        for (var i = 0; i < props.runs().maxQueued(); i++) {
            setRun(start(sessionOf(token)).getBody().get("runId"), "CREATED");
        }

        assertThat(start(sessionOf(token)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("an invite that expired after the session opened starts no run, and the session still reads its runs")
    void an_invite_that_expired_after_the_session_opened_starts_no_run_and_the_session_still_reads_its_runs() {
        var token = invite(1);
        var sessionId = sessionOf(token);
        var other = sessionOf(token);
        var runId = start(sessionId).getBody().get("runId");
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 second' WHERE token = :token").param("token", token).update();

        var response = start(other);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).isEqualTo(ControlErrors.INVITE_NOT_VALID);
        assertThat(getRun(sessionId, runId).getBody().get("state")).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("a run of another session is 404, the same as an unknown one")
    void a_run_of_another_session_is_404_the_same_as_an_unknown_one() {
        var token = invite();
        var runId = start(sessionOf(token)).getBody().get("runId");
        var other = sessionOf(token);

        var foreign = getRun(other, runId);
        var unknown = getRun(other, UUID.randomUUID());

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody()).isEqualTo(unknown.getBody()).isEqualTo(Map.of("error", ControlErrors.RUN_NOT_FOUND));
    }

    @Test
    @DisplayName("a session lists only its runs, newest first")
    void a_session_lists_only_its_runs_newest_first() {
        var token = invite();
        var sessionId = sessionOf(token);
        var older = start(sessionId).getBody().get("runId");
        setRun(older, "RECONCILED");
        start(sessionOf(token));
        var newer = start(sessionId).getBody().get("runId");

        var items = (List<?>) get("/api/sessions/" + sessionId + "/runs").getBody().get("items");

        assertThat(items.stream().<Object>map(it -> ((Map<?, ?>) it).get("runId")).toList()).containsExactly(newer, older);
        assertThat(get("/api/sessions/" + sessionOf(token) + "/runs").getBody().get("items")).isEqualTo(List.of());
    }

    @Test
    void concurrent_starts_from_different_invites_beyond_the_queue_get_exactly_its_free_places() throws Exception {
        var sessions = Stream.generate(() -> sessionOf(invite())).limit(3L * props.runs().maxQueued()).toList().iterator();

        var statuses = concurrently(3 * props.runs().maxQueued(), () -> {
            UUID session;
            synchronized (sessions) {
                session = sessions.next();
            }
            return start(session).getStatusCode();
        });

        assertThat(count(statuses, HttpStatus.ACCEPTED)).isEqualTo(props.runs().maxQueued());
        assertThat(count(statuses, HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(2 * props.runs().maxQueued());
        var positions = jdbc.sql(
            "SELECT count(*) FROM runs WHERE state = 'QUEUED'").query(Long.class).single();
        assertThat(positions).isEqualTo((long) props.runs().maxQueued());
    }

    @Test
    void concurrent_starts_beyond_the_quota_get_exactly_the_quota() throws Exception {
        var token = invite(3);

        var statuses = concurrently(12, () -> start(sessionOf(token)).getStatusCode());

        assertThat(count(statuses, HttpStatus.ACCEPTED)).isEqualTo(3);
        assertThat(runsOf(token)).isEqualTo(3);
    }

    @Test
    @DisplayName("a start while a run of the session is not final is refused, whatever state that run is in")
    void a_start_while_a_run_of_the_session_is_not_final_is_refused_whatever_state_that_run_is_in() {
        var sessionId = sessionOf(invite());
        var runId = start(sessionId).getBody().get("runId");

        List.of("QUEUED", "CREATED", "RUNNING", "DRAINING").forEach(state -> {
            setRun(runId, state);
            var response = start(sessionId);
            assertThat(response.getStatusCode()).as(state).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().get("error")).as(state).isEqualTo(ControlErrors.RUN_NOT_FINAL);
        });
    }

    @Test
    void a_final_run_does_not_hold_back_the_next_start() {
        var sessionId = sessionOf(invite());

        List.of("RECONCILED", "FAILED", "EXPIRED").forEach(state -> {
            setRun(start(sessionId).getBody().get("runId"), state);
            assertThat(start(sessionId).getStatusCode()).as(state).isEqualTo(HttpStatus.ACCEPTED);
            jdbc.sql("UPDATE runs SET state = 'EXPIRED' WHERE session_id = :sessionId").param("sessionId", sessionId).update();
        });
    }

    @Test
    @DisplayName("concurrent starts in one session make one run, the others are refused")
    void concurrent_starts_in_one_session_make_one_run_the_others_are_refused() throws Exception {
        var token = invite();
        var sessionId = sessionOf(token);

        var statuses = concurrently(8, () -> start(sessionId).getStatusCode());

        assertThat(count(statuses, HttpStatus.ACCEPTED)).isEqualTo(1);
        assertThat(count(statuses, HttpStatus.CONFLICT)).isEqualTo(7);
        assertThat(runsOf(token)).isEqualTo(1);
    }

    @Test
    void concurrent_repeats_of_one_requestId_make_one_run() throws Exception {
        var token = invite();
        var sessionId = sessionOf(token);
        var requestId = UUID.randomUUID();

        List<ResponseEntity<Map<String, Object>>> responses = concurrently(8, () -> start(sessionId, requestId));

        assertThat(responses.stream().map(ResponseEntity::getStatusCode).toList()).containsOnly(HttpStatus.ACCEPTED);
        assertThat(responses.stream().map(it -> it.getBody().get("runId")).collect(Collectors.toSet())).hasSize(1);
        assertThat(runsOf(token)).isEqualTo(1);
    }
}
