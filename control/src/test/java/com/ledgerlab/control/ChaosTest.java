package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.books;
import static com.ledgerlab.control.RunFixtures.clientReport;
import static com.ledgerlab.control.RunFixtures.mapOf;
import static com.ledgerlab.control.RunFixtures.stats;
import static com.ledgerlab.control.RunFixtures.summary;
import static com.ledgerlab.control.http.ServiceHttp.forRun;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.reporting.ReportingApi;
import com.ledgerlab.control.runs.Conductor;
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

    @Autowired Conductor conductor;

    private ResponseEntity<Map<String, Object>> chaos(Object sessionId) {
        return post("/api/sessions/" + sessionId + "/chaos", null);
    }

    private Map<String, Object> session(UUID sessionId) {
        return get("/api/sessions/" + sessionId).getBody();
    }

    private UUID runIn(UUID sessionId, String state) {
        var runId = UUID.fromString((String) start(sessionId).getBody().get("runId"));
        jdbc.sql(
            """
            UPDATE runs SET state = :state, admitted_at = clock_timestamp(),
                started_at = CASE WHEN :state <> 'QUEUED' THEN clock_timestamp() END
            WHERE run_id = :id
            """).param("state", state).param("id", runId).update();
        return runId;
    }

    private UUID sessionWithRunning() {
        var sessionId = sessionOf(invite());
        runIn(sessionId, "RUNNING");
        return sessionId;
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
        var answer = chaos(sessionWithRunning());

        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(answer.getBody().get("instances")).isEqualTo(List.of("ledger-0", "ledger-1", "ledger-2"));
        assertThat(answer.getBody().get("at")).isNotNull();
        assertThat(crashCalls()).containsExactly(1, 1, 1);
        assertThat(single(events()).get("instances")).isEqualTo("ledger-0,ledger-1,ledger-2");
    }

    @Test
    void instances_that_did_not_answer_are_left_out_of_the_answer() {
        ledgerInstances.get(1).status = 500;
        ledgerInstances.get(2).delay = Duration.ofMillis(800);

        var answer = chaos(sessionWithRunning());

        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(answer.getBody().get("instances")).isEqualTo(List.of("ledger-0"));
        assertThat(single(events()).get("instances")).isEqualTo("ledger-0");
    }

    @Test
    @DisplayName("chaos answers 503 when no instance answered, and the minute is not used")
    void chaos_answers_503_when_no_instance_answered_and_the_minute_is_not_used() {
        ledgerInstances.forEach(it -> it.status = 503);
        var sessionId = sessionWithRunning();

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
    void chaos_without_a_running_run_of_this_session_is_409() {
        var sessionId = sessionOf(invite());
        var other = sessionWithRunning();
        assertThat(chaos(sessionId).getStatusCode().value()).as("no run").isEqualTo(409);

        List.of("QUEUED", "CREATED", "DRAINING").forEach(state -> {
            var runId = runIn(sessionId, state);
            var answer = chaos(sessionId);
            assertThat(answer.getStatusCode().value()).as(state).isEqualTo(409);
            assertThat(answer.getBody().get("error")).isEqualTo("no running run");
            jdbc.sql("DELETE FROM runs WHERE run_id = :id").param("id", runId).update();
        });
        assertThat(crashCalls()).containsExactly(0, 0, 0);
        assertThat(chaos(other).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("a stopped run is draining, so chaos after the stop is 409")
    void a_stopped_run_is_draining_so_chaos_after_the_stop_is_409() {
        var sessionId = sessionOf(invite());
        var runId = runIn(sessionId, "RUNNING");
        assertThat(post("/api/sessions/" + sessionId + "/runs/" + runId + "/stop", null).getStatusCode().value()).isEqualTo(202);
        jdbc.sql("UPDATE runs SET state = 'DRAINING' WHERE run_id = :id").param("id", runId).update();

        assertThat(chaos(sessionId).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("a second chaos within the minute is 429 with Retry-After of the time left")
    void a_second_chaos_within_the_minute_is_429_with_Retry_After_of_the_time_left() {
        var sessionId = sessionWithRunning();
        assertThat(chaos(sessionId).getStatusCode().value()).isEqualTo(202);

        var again = chaos(sessionWithRunning());
        assertThat(again.getStatusCode().value()).isEqualTo(429);
        assertThat(again.getBody().get("error")).isEqualTo("chaos used recently");
        assertThat(again.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");

        backdateChaos(Duration.ofMillis(30_500));
        assertThat(chaos(sessionId).getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(crashCalls()).containsExactly(1, 1, 1);
        assertThat(events()).hasSize(1);
    }

    @Test
    void of_two_simultaneous_chaos_requests_exactly_one_crashes_the_ledger() throws Exception {
        ledgerInstances.forEach(it -> it.delay = Duration.ofMillis(200));
        var sessions = List.of(sessionWithRunning(), sessionWithRunning());
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
        assertThat(Integer.parseInt(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1, 60);
        assertThat(crashCalls()).containsExactly(1, 1, 1);
        assertThat(events()).hasSize(1);
    }

    @Test
    void the_session_counts_down_to_the_next_chaos_and_reads_0_once_the_minute_has_passed() {
        var sessionId = sessionWithRunning();
        assertThat(session(sessionId).get("chaosAvailable")).isEqualTo(true);
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(0);

        chaos(sessionId);
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(60);
        backdateChaos(Duration.ofMillis(45_200));
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(15);
        backdateChaos(Duration.ofSeconds(15));
        assertThat(session(sessionId).get("chaosReadyInSeconds")).isEqualTo(0);
        assertThat(chaos(sessionId).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    @DisplayName("chaosReadyInSeconds equals Retry-After of a refused chaos")
    void chaosReadyInSeconds_equals_Retry_After_of_a_refused_chaos() {
        var sessionId = sessionWithRunning();
        chaos(sessionId);
        backdateChaos(Duration.ofMillis(12_345));

        var retryAfter = chaos(sessionId).getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

        assertThat(session(sessionId).get("chaosReadyInSeconds").toString()).isEqualTo(retryAfter);
    }

    @Test
    @DisplayName("the report of every run active at the crash lists its offset, a run started after it lists none")
    void the_report_of_every_run_active_at_the_crash_lists_its_offset_a_run_started_after_it_lists_none() {
        var pressing = sessionOf(invite());
        var hit = runIn(pressing, "RUNNING");
        var otherSession = sessionOf(invite());
        var hitInOtherSession = runIn(otherSession, "RUNNING");
        var at = chaos(pressing).getBody().get("at");
        jdbc.sql("UPDATE runs SET started_at = CAST(:at AS timestamptz) - interval '10 seconds' WHERE run_id = :id")
            .param("at", at).param("id", hit).update();
        var laterSession = sessionOf(invite());
        var later = runIn(laterSession, "RUNNING");

        List.of(hit, hitInOtherSession, later).forEach(this::drainAndReconcile);

        assertThat(reportOf(pressing, hit).get("chaos")).isEqualTo(List.of(mapOf("offsetMs", 10_000, "instances", List.of("ledger-0", "ledger-1", "ledger-2"))));
        assertThat((List<?>) reportOf(otherSession, hitInOtherSession).get("chaos")).hasSize(1);
        assertThat(reportOf(laterSession, later).get("chaos")).isEqualTo(List.of());
    }

    private void drainAndReconcile(UUID runId) {
        jdbc.sql(
            """
            UPDATE runs SET state = 'DRAINING', drain_started_at = clock_timestamp(), closed_at = clock_timestamp(),
                drain_progress = 0, drain_progress_at = clock_timestamp()
            WHERE run_id = :id
            """).param("id", runId).update();
        gateway.answer("GET", forRun(GatewayApi.SUMMARY_PATH, runId), 200, summary(runId));
        ledger.answer("GET", forRun(LedgerApi.RECONCILIATION_PATH, runId), 200, books(runId));
        reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 200, stats(runId));
        generator.answer("GET", forRun(GeneratorApi.RECONCILIATION_PATH, runId), 200, clientReport(runId));
        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("RECONCILED");
    }

    private Map<String, Object> reportOf(UUID sessionId, UUID runId) {
        return get("/api/sessions/" + sessionId + "/runs/" + runId + "/report").getBody();
    }
}
