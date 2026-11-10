package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.duplicates;
import static com.ledgerlab.control.RunFixtures.mapOf;
import static com.ledgerlab.control.RunFixtures.prometheus;
import static com.ledgerlab.control.RunFixtures.sample;
import static com.ledgerlab.control.RunFixtures.simulation;
import static com.ledgerlab.control.RunFixtures.stats;
import static com.ledgerlab.control.http.ServiceHttp.forRun;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.reporting.ReportingApi;
import com.ledgerlab.control.runs.Conductor;
import com.ledgerlab.control.runs.RunQueue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class LiveRunsTest extends IntegrationTest {

    private static final String QUERY_PATH = "/api/v1/query";
    private static final String UP = "up{lab_service=\"true\"}";
    private static final String PARTITIONS = "ledger_kafka_assigned_partitions";
    private static final String DUPLICATES = "sum by (run) (ledger_duplicates_total)";

    @Autowired Conductor conductor;
    @Autowired RunQueue queue;

    private record Running(UUID sessionId, UUID runId) {
    }

    private Running running() {
        var sessionId = sessionOf(invite());
        var runId = UUID.fromString((String) start(sessionId).getBody().get("runId"));
        queue.advance(true);
        jdbc.sql("UPDATE runs SET state = 'RUNNING', started_at = clock_timestamp() WHERE run_id = :id").param("id", runId).update();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200, simulation(runId, "RUNNING"));
        return new Running(sessionId, runId);
    }

    private Map<?, ?> live(UUID sessionId, UUID runId) {
        return (Map<?, ?>) getRun(sessionId, runId).getBody().get("live");
    }

    private void prometheusAnswers(String query, Object body) {
        prometheus.answerQuery("GET", QUERY_PATH, "query=" + query, 200, body);
    }

    @Test
    @DisplayName("a running run shows the bank's numbers, the clients' counts, the pods and the ledger's duplicates")
    void a_running_run_shows_the_banks_numbers_the_clients_counts_the_pods_and_the_ledgers_duplicates() {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 200, stats(runId, 500, 480, 5));
        prometheusAnswers(UP, prometheus(List.of(sample(pod("ledger", "a:8080"), 1), sample(pod("ledger", "b:8080"), 0), sample(pod("gateway", "c:8081"), 1))));
        prometheusAnswers(PARTITIONS, prometheus(List.of(sample(pod("ledger", "a:8080"), 6))));
        prometheusAnswers(DUPLICATES, duplicates(runId, 3));

        conductor.tick();

        var live = Objects.requireNonNull(live(sessionId, runId));
        assertThat(((Map<?, ?>) ((Map<?, ?>) live.get("stats")).get("totals")).get("inFlight")).isEqualTo(15);
        assertThat(((Map<?, ?>) live.get("clients")).get("requested")).isEqualTo(1000);
        assertThat(live.get("pods")).isEqualTo(
            List.of(
                mapOf("job", "gateway", "instance", "c:8081", "up", true, "partitions", null),
                mapOf("job", "ledger", "instance", "a:8080", "up", true, "partitions", 6),
                mapOf("job", "ledger", "instance", "b:8080", "up", false, "partitions", null)));
        assertThat(live.get("ledgerDuplicatesAtLeast")).isEqualTo(3);
    }

    @Test
    void each_part_keeps_its_last_numbers_while_its_source_cannot_be_read() {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 200, stats(runId, 500, RunFixtures.POSTED, RunFixtures.REJECTED));
        prometheusAnswers(UP, prometheus(List.of(sample(pod("ledger", "a:8080"), 1))));
        conductor.tick();

        reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 503);
        prometheus.reset();
        prometheus.status = 503;
        conductor.tick();

        var live = Objects.requireNonNull(live(sessionId, runId));
        assertThat(((Map<?, ?>) ((Map<?, ?>) live.get("stats")).get("totals")).get("accepted")).isEqualTo(500);
        assertThat((List<?>) live.get("pods")).hasSize(1);
    }

    @Test
    @DisplayName("the ledger's duplicates keep the most seen, as an instance that dies takes its count along")
    void the_ledgers_duplicates_keep_the_most_seen_as_an_instance_that_dies_takes_its_count_along() {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        prometheusAnswers(DUPLICATES, duplicates(runId, 9));
        conductor.tick();

        prometheusAnswers(DUPLICATES, duplicates(runId, 4));
        conductor.tick();

        assertThat(live(sessionId, runId).get("ledgerDuplicatesAtLeast")).isEqualTo(9);
    }

    @Test
    @DisplayName("a run Prometheus has no duplicates of shows zero, not unknown")
    void a_run_Prometheus_has_no_duplicates_of_shows_zero_not_unknown() {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        prometheusAnswers(DUPLICATES, duplicates(UUID.randomUUID(), 2));

        conductor.tick();

        assertThat(live(sessionId, runId).get("ledgerDuplicatesAtLeast")).isEqualTo(0);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a failure while reading live numbers is logged, not lost in a future")
    void a_failure_while_reading_live_numbers_is_logged_not_lost_in_a_future(CapturedOutput output) {
        running();
        var noValue = mapOf("status", "success", "data", mapOf("resultType", "vector", "result", List.of(mapOf("metric", mapOf("job", "ledger"), "value", List.of()))));
        prometheusAnswers(UP, noValue);

        conductor.tick();

        assertThat(output.getOut()).contains("Live numbers not read");
    }

    @Test
    @DisplayName("without any answer yet the parts are null, and a run past draining has no live numbers")
    void without_any_answer_yet_the_parts_are_null_and_a_run_past_draining_has_no_live_numbers() {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        reporting.status = 503;
        prometheus.status = 503;
        conductor.tick();

        var live = Objects.requireNonNull(live(sessionId, runId));
        assertThat(live.get("stats")).isNull();
        assertThat(live.get("pods")).isNull();
        assertThat(live.get("ledgerDuplicatesAtLeast")).isNull();

        setRun(runId, "FAILED");
        assertThat(live(sessionId, runId)).isNull();
    }

    private Map<String, String> pod(String job, String instance) {
        return Map.of("job", job, "instance", instance);
    }
}
