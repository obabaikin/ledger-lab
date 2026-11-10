package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.runs.RunQueue;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class RunStopAndReportTest extends IntegrationTest {

    @Autowired RunQueue queue;

    private record QueuedRun(UUID sessionId, Object runId) {
    }

    private ResponseEntity<Map<String, Object>> stop(Object sessionId, Object runId) {
        return post("/api/sessions/" + sessionId + "/runs/" + runId + "/stop", null);
    }

    private ResponseEntity<Map<String, Object>> report(Object sessionId, Object runId) {
        return get("/api/sessions/" + sessionId + "/runs/" + runId + "/report");
    }

    private QueuedRun queuedRun() {
        var sessionId = sessionOf(invite());
        return new QueuedRun(sessionId, start(sessionId).getBody().get("runId"));
    }

    @Test
    void a_run_that_has_not_started_cannot_be_stopped() {
        var run = queuedRun();
        var sessionId = run.sessionId();
        var queued = run.runId();
        assertThat(stop(sessionId, queued).getStatusCode().value()).isEqualTo(409);
        assertThat(stop(sessionId, queued).getBody().get("error")).isEqualTo("run not started");

        queue.advance(true);
        assertThat(stateOf(queued)).isEqualTo("CREATED");
        assertThat(stop(sessionId, queued).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("a stop of a running run is taken once, a repeat answers the same")
    void a_stop_of_a_running_run_is_taken_once_a_repeat_answers_the_same() {
        var run = queuedRun();
        var sessionId = run.sessionId();
        var runId = run.runId();
        setRun(runId, "RUNNING");

        var first = stop(sessionId, runId);
        var firstAt = jdbc.sql("SELECT stop_requested_at FROM runs WHERE run_id = CAST(:id AS uuid)").param("id", runId.toString()).query(Timestamp.class).single();
        var repeat = stop(sessionId, runId);

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(repeat.getStatusCode().value()).isEqualTo(202);
        assertThat(repeat.getBody().get("stopRequested")).isEqualTo(true);
        assertThat(jdbc.sql("SELECT stop_requested_at FROM runs WHERE run_id = CAST(:id AS uuid)").param("id", runId.toString()).query(Timestamp.class).single()).isEqualTo(firstAt);
    }

    @Test
    void a_stop_after_the_clients_ended_answers_200_and_changes_nothing() {
        var run = queuedRun();
        var sessionId = run.sessionId();
        var runId = run.runId();
        setRun(runId, "DRAINING");

        var answer = stop(sessionId, runId);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody().get("stopRequested")).isEqualTo(false);
    }

    @Test
    void stop_and_report_name_an_unknown_session_before_an_unknown_run() {
        var sessionId = queuedRun().sessionId();

        assertThat(stop(UUID.randomUUID(), UUID.randomUUID()).getBody().get("error")).isEqualTo("session not found");
        assertThat(stop(sessionId, UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        assertThat(stop(sessionId, UUID.randomUUID()).getBody().get("error")).isEqualTo("run not found");
        assertThat(report(UUID.randomUUID(), UUID.randomUUID()).getBody().get("error")).isEqualTo("session not found");
        assertThat(report(sessionId, UUID.randomUUID()).getBody().get("error")).isEqualTo("run not found");
    }

    @Test
    void a_run_of_another_session_cannot_be_stopped_or_read() {
        var runId = queuedRun().runId();
        var other = sessionOf(invite());
        setRun(runId, "RUNNING");

        assertThat(stop(other, runId).getStatusCode().value()).isEqualTo(404);
        assertThat(report(other, runId).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void there_is_no_report_before_the_run_is_reconciled() {
        var run = queuedRun();
        var sessionId = run.sessionId();
        var runId = run.runId();

        List.of("QUEUED", "RUNNING", "DRAINING", "FAILED").forEach(state -> {
            setRun(runId, state);
            var answer = report(sessionId, runId);
            assertThat(answer.getStatusCode().value()).as(state).isEqualTo(409);
            assertThat(answer.getBody().get("error")).isEqualTo("report not ready");
        });
    }

    @Test
    @DisplayName("the chaos button answers that it is not available, and the session says so")
    void the_chaos_button_answers_that_it_is_not_available_and_the_session_says_so() {
        var sessionId = sessionOf(invite());

        var answer = post("/api/sessions/" + sessionId + "/chaos", null);

        assertThat(answer.getStatusCode().value()).isEqualTo(501);
        assertThat(answer.getBody().get("error")).isEqualTo("chaos not available");
        assertThat(get("/api/sessions/" + sessionId).getBody().get("chaosAvailable")).isEqualTo(false);
        assertThat(get("/api/sessions/" + sessionId).getBody().get("chaosReadyInSeconds")).isEqualTo(0);
        assertThat(ledgerInstances.stream().flatMap(it -> it.calls.stream()).toList()).isEmpty();
        assertThat(post("/api/sessions/" + UUID.randomUUID() + "/chaos", null).getStatusCode().value()).isEqualTo(404);
    }
}
