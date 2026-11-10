package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.books;
import static com.ledgerlab.control.RunFixtures.clientReport;
import static com.ledgerlab.control.RunFixtures.duplicates;
import static com.ledgerlab.control.RunFixtures.simulation;
import static com.ledgerlab.control.RunFixtures.stats;
import static com.ledgerlab.control.RunFixtures.summary;
import static com.ledgerlab.control.http.ServiceHttp.forRun;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.reporting.ReportingApi;
import com.ledgerlab.control.runs.Conductor;
import com.ledgerlab.control.runs.RunQueue;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class ConductorTest extends IntegrationTest {

    private static final String PROMETHEUS_QUERY = "/api/v1/query";

    @Autowired Conductor conductor;
    @Autowired RunQueue queue;

    private final String closedAt = "2026-10-12T01:00:00Z";

    private UUID admitted() {
        var runId = UUID.fromString((String) start(sessionOf(invite())).getBody().get("runId"));
        queue.advance(true);
        return runId;
    }

    private UUID running() {
        var runId = admitted();
        jdbc.sql("UPDATE runs SET state = 'RUNNING', started_at = clock_timestamp() WHERE run_id = :id").param("id", runId).update();
        return runId;
    }

    private UUID draining() {
        return draining(true, false);
    }

    private UUID draining(boolean closed, boolean clientLost) {
        var runId = running();
        jdbc.sql(
            """
            UPDATE runs SET state = 'DRAINING', drain_started_at = clock_timestamp(), client_lost = :clientLost,
                closed_at = CASE WHEN :closed THEN clock_timestamp() END,
                drain_progress = CASE WHEN :closed THEN 0 END,
                drain_progress_at = CASE WHEN :closed THEN clock_timestamp() END
            WHERE run_id = :id
            """).param("id", runId).param("closed", closed).param("clientLost", clientLost).update();
        return runId;
    }

    private void backdate(UUID runId, String column, Duration by) {
        jdbc.sql("UPDATE runs SET " + column + " = " + column + " - CAST(:millis AS bigint) * interval '1 millisecond' WHERE run_id = :id")
            .param("millis", by.toMillis())
            .param("id", runId)
            .update();
    }

    private Object column(UUID runId, String column) {
        return single(jdbc.sql("SELECT " + column + " FROM runs WHERE run_id = :id").param("id", runId).query((rs, row) -> rs.getObject(1)).list());
    }

    private void bank(UUID runId) {
        bank(runId, summary(runId), books(runId), stats(runId));
    }

    private void bankWithSummary(UUID runId, Map<String, Object> summary) {
        bank(runId, summary, books(runId), stats(runId));
    }

    private void bankWithBooks(UUID runId, Map<String, Object> books) {
        bank(runId, summary(runId), books, stats(runId));
    }

    private void bankWithStats(UUID runId, Map<String, Object> stats) {
        bank(runId, summary(runId), books(runId), stats);
    }

    private void bank(UUID runId, Map<String, Object> summary, Map<String, Object> books, Map<String, Object> stats) {
        gateway.answer("GET", forRun(GatewayApi.SUMMARY_PATH, runId), 200, summary);
        ledger.answer("GET", forRun(LedgerApi.RECONCILIATION_PATH, runId), 200, books);
        if (stats != null) {
            reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 200, stats);
        }
    }

    private void clientReconciles(UUID runId) {
        clientReconciles(runId, 200, clientReport(runId));
    }

    private void clientReconciles(UUID runId, int status, Object body) {
        generator.answer("GET", forRun(GeneratorApi.RECONCILIATION_PATH, runId), status, body);
    }

    private Map<String, Object> reportOf(UUID sessionRun) {
        var sessionId = jdbc.sql("SELECT session_id FROM runs WHERE run_id = :id").param("id", sessionRun).query(UUID.class).single();
        return get("/api/sessions/" + sessionId + "/runs/" + sessionRun + "/report").getBody();
    }

    @Test
    @DisplayName("a created run gets its accounts, then its clients, and runs")
    void a_created_run_gets_its_accounts_then_its_clients_and_runs() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404, Map.of("error", "No simulation " + runId));
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(runId, "RUNNING"));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RUNNING");
        assertThat(column(runId, "started_at")).isNotNull();
        var accounts = objectMapper.readTree(single(ledger.callsTo("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId))).body());
        assertThat(accounts.get("accountCount").asInt()).isEqualTo(100);
        assertThat(accounts.get("openingBalanceCents").asLong()).isEqualTo(100_000_000);
        assertThat(accounts.get("blockedAccounts").asInt()).isEqualTo(2);
        assertThat(accounts.get("dailyLimitCents").asLong()).isEqualTo(20_000_000);
        var simulation = objectMapper.readTree(single(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).body());
        assertThat(simulation.propertyNames()).containsExactlyInAnyOrder("runId", "tps", "durationSeconds", "accountCount", "blockedAccounts");
        assertThat(simulation.get("accountCount").asInt()).isEqualTo(100);
        assertThat(simulation.get("blockedAccounts").asInt()).isEqualTo(2);
    }

    @Test
    void accounts_an_earlier_attempt_already_opened_count_as_opened() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 409, Map.of("error", "Run " + runId + " already has accounts"));
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(runId, "RUNNING"));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RUNNING");
    }

    @Test
    void clients_the_generator_already_runs_are_not_started_again() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200, simulation(runId, "FINISHED"));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RUNNING");
        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).isEmpty();
        assertThat(ledger.callsTo("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId))).isEmpty();
    }

    @Test
    void a_setup_the_ledger_rejects_fails_the_run() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 400, Map.of("error", "invalid"));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("FAILED");
        assertThat(column(runId, "failure")).isEqualTo("setup rejected");
        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).isEmpty();
    }

    @Test
    void a_setup_the_generator_rejects_fails_the_run() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 400, Map.of("error", "invalid"));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("FAILED");
        assertThat(column(runId, "failure")).isEqualTo("setup rejected");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a setup that keeps failing is retried, then fails after the setup timeout and logs the last answers")
    void a_setup_that_keeps_failing_is_retried_then_fails_after_the_setup_timeout_and_logs_the_last_answers(CapturedOutput output) {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 409, Map.of("error", "journals in memory hold 300000"));

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("CREATED");

        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 503, Map.of("error", "gateway unavailable"));
        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("CREATED");

        backdate(runId, "setup_started_at", props.runs().setupTimeout());
        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("FAILED");
        assertThat(column(runId, "failure")).isEqualTo("setup timed out");
        var line = single(output.getOut().lines().filter(it -> it.contains("setup timed out") && it.contains(runId.toString())).toList());
        assertThat(line).contains("generator status: 404", "ledger accounts: answered 201", "generator start: 503 gateway unavailable");
    }

    @Test
    void a_run_first_tried_long_after_it_left_the_queue_gets_its_whole_setup_time() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(runId, "RUNNING"));
        backdate(runId, "admitted_at", props.runs().setupTimeout().plusSeconds(60));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RUNNING");
    }

    @Test
    void a_setup_that_times_out_after_a_start_that_may_have_arrived_stops_the_clients() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 503);
        conductor.tick();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 503);

        backdate(runId, "setup_started_at", props.runs().setupTimeout());
        conductor.tick();

        assertThat(column(runId, "failure")).isEqualTo("setup timed out");
        assertThat(generator.callsTo("POST", forRun(GeneratorApi.STOP_PATH, runId))).hasSize(1);
    }

    @Test
    void a_setup_that_times_out_with_no_start_sent_stops_nothing() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 409, Map.of("error", "journals in memory hold 300000"));
        conductor.tick();

        backdate(runId, "setup_started_at", props.runs().setupTimeout());
        conductor.tick();

        assertThat(column(runId, "failure")).isEqualTo("setup timed out");
        assertThat(generator.callsTo("POST", forRun(GeneratorApi.STOP_PATH, runId))).isEmpty();
    }

    @Test
    void a_start_whose_answer_was_lost_is_not_sent_again_when_the_generator_forgot_the_run() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 503);

        conductor.tick();
        conductor.tick();

        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).hasSize(1);
        assertThat(stateOf(runId)).isEqualTo("RUNNING");
        conductor.tick();
        assertThat(column(runId, "client_lost")).isEqualTo(true);
    }

    @Test
    void a_start_the_generator_refused_is_sent_again() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 409, Map.of("error", "journals in memory hold 300000"));

        conductor.tick();
        conductor.tick();

        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).hasSize(2);
        assertThat(stateOf(runId)).isEqualTo("CREATED");
    }

    @Test
    void an_answer_control_cannot_read_does_not_keep_the_run_past_its_deadline() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200, "{\"state\":\"WARMING_UP\"}");

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("CREATED");

        backdate(runId, "setup_started_at", props.runs().setupTimeout());
        conductor.tick();
        assertThat(column(runId, "failure")).isEqualTo("setup timed out");
    }

    @Test
    void a_slow_step_of_one_run_does_not_hold_up_another_run() {
        var slow = draining();
        bank(slow);
        clientReconciles(slow);
        generator.answer("GET", forRun(GeneratorApi.RECONCILIATION_PATH, slow), 200, clientReport(slow), props.generator().reconciliationTimeout().minusMillis(100));
        backdate(slow, "admitted_at", Duration.ofHours(1));
        var fresh = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, fresh), 404);
        ledger.answer("POST", forRun(LedgerApi.ACCOUNTS_PATH, fresh), 201, List.of());
        generator.answer("POST", GeneratorApi.SIMULATIONS_PATH, 201, simulation(fresh, "RUNNING"));
        jdbc.sql("UPDATE runs SET setup_started_at = clock_timestamp() - CAST(:millis AS bigint) * interval '1 millisecond' WHERE run_id = :id")
            .param("millis", props.runs().setupTimeout().minusMillis(300).toMillis())
            .param("id", fresh)
            .update();

        conductor.tick();

        assertThat(stateOf(fresh)).isEqualTo("RUNNING");
        assertThat(stateOf(slow)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("a generator that does not answer in time is asked again, not started over")
    void a_generator_that_does_not_answer_in_time_is_asked_again_not_started_over() {
        var runId = admitted();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404, null, props.services().requestTimeout().plusMillis(300));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("CREATED");
        assertThat(ledger.callsTo("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId))).isEmpty();
        assertThat(generator.callsTo("POST", GeneratorApi.SIMULATIONS_PATH)).isEmpty();
    }

    @Test
    void clients_that_finished_or_were_stopped_hand_the_run_over_to_the_drain() {
        var finished = running();
        var stopped = running();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, finished), 200, simulation(finished, "FINISHED"));
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, stopped), 200, simulation(stopped, "STOPPED"));

        conductor.tick();

        assertThat(List.of(stateOf(finished), stateOf(stopped))).containsOnly("DRAINING");
        assertThat(column(finished, "client_lost")).isEqualTo(false);
    }

    @Test
    void active_clients_keep_the_run_running() {
        var runId = running();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200, simulation(runId, "STOPPING"));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("clients the generator no longer knows are lost, and the run still drains")
    void clients_the_generator_no_longer_knows_are_lost_and_the_run_still_drains() {
        var runId = running();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 404);

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("DRAINING");
        assertThat(column(runId, "client_lost")).isEqualTo(true);
    }

    @Test
    void clients_still_active_past_the_duration_and_the_grace_are_stopped_and_the_run_drains() {
        var runId = running();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200, simulation(runId, "RUNNING"));
        backdate(runId, "started_at", Duration.ofSeconds(10).plus(props.runs().runningGrace()));

        conductor.tick();

        assertThat(generator.callsTo("POST", forRun(GeneratorApi.STOP_PATH, runId))).hasSize(1);
        assertThat(stateOf(runId)).isEqualTo("DRAINING");
    }

    @Test
    void a_requested_stop_is_passed_to_the_generator_on_the_next_tick() {
        var runId = running();
        var sessionId = column(runId, "session_id");
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200, simulation(runId, "STOPPING"));

        var answer = post("/api/sessions/" + sessionId + "/runs/" + runId + "/stop", null);
        assertThat(answer.getStatusCode().value()).isEqualTo(202);
        assertThat(answer.getBody().get("stopRequested")).isEqualTo(true);

        conductor.tick();

        assertThat(generator.callsTo("POST", forRun(GeneratorApi.STOP_PATH, runId))).hasSize(1);
    }

    @Test
    void the_drain_starts_by_closing_the_period() {
        var runId = draining(false, false);
        gateway.answer("POST", forRun(GatewayApi.CLOSE_PATH, runId), 200, Map.of("runId", runId, "closedAt", closedAt));

        conductor.tick();

        assertThat(column(runId, "closed_at")).isNotNull();
        assertThat(column(runId, "drain_progress")).isEqualTo(0L);
    }

    @Test
    void a_close_that_keeps_failing_fails_the_run_after_the_drain_timeout() {
        var runId = draining(false, false);
        gateway.answer("POST", forRun(GatewayApi.CLOSE_PATH, runId), 503, Map.of("error", "lock timeout"));

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("DRAINING");

        backdate(runId, "drain_started_at", props.runs().drainTimeout());
        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("FAILED");
        assertThat(column(runId, "failure")).isEqualTo("period not closed");
    }

    @Test
    void a_drained_run_is_reconciled_once_and_its_report_kept() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId);

        conductor.tick();
        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RECONCILED");
        assertThat(column(runId, "result")).isEqualTo("BALANCED");
        assertThat(generator.callsTo("GET", forRun(GeneratorApi.RECONCILIATION_PATH, runId))).hasSize(1);
        var report = reportOf(runId);
        assertThat(report.get("result")).isEqualTo("BALANCED");
        assertThat((List<?>) report.get("reasons")).isEmpty();
        assertThat(report.get("bankViewsAgree")).isEqualTo(true);
        assertThat(report.get("rejectionsByReason")).isEqualTo(Map.of("BLOCKED_ACCOUNT", 15));
    }

    @Test
    void the_client_is_asked_only_once_the_gateway_knows_every_result() {
        var runId = draining();
        bankWithSummary(runId, summary(runId, 900, 15));
        clientReconciles(runId);

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("DRAINING");
        assertThat(generator.callsTo("GET", forRun(GeneratorApi.RECONCILIATION_PATH, runId))).isEmpty();
    }

    @Test
    @DisplayName("a drain that stops making progress ends incomplete, with how far it got")
    void a_drain_that_stops_making_progress_ends_incomplete_with_how_far_it_got() {
        var runId = draining();
        bankWithBooks(runId, books(runId, 700, 10));
        clientReconciles(runId);

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("DRAINING");

        backdate(runId, "drain_progress_at", props.runs().drainStallTimeout());
        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RECONCILED");
        var report = reportOf(runId);
        assertThat(report.get("result")).isEqualTo("INCOMPLETE");
        assertThat(report.get("reasons")).isEqualTo(List.of("drain timed out: ledger applied 710, gateway results 995, of 995"));
        assertThat(report.get("bankViewsAgree")).isNull();
    }

    @Test
    void progress_in_the_ledger_keeps_the_drain_waiting() {
        var runId = draining();
        bankWithBooks(runId, books(runId, 700, 10));
        backdate(runId, "drain_progress_at", props.runs().drainStallTimeout());

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("DRAINING");
        assertThat(column(runId, "drain_progress")).isEqualTo(710L + 995L);
    }

    @Test
    void a_gateway_still_catching_up_on_results_keeps_the_drain_waiting_while_the_ledger_is_done() {
        var runId = draining();
        bankWithSummary(runId, summary(runId, 900, 15));
        clientReconciles(runId);
        conductor.tick();
        backdate(runId, "drain_progress_at", props.runs().drainStallTimeout());
        bankWithSummary(runId, summary(runId, 950, 15));

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("DRAINING");
    }

    @Test
    void more_results_than_accepted_transfers_end_the_drain_at_once_as_a_break() {
        var runId = draining();
        bankWithBooks(runId, books(runId, 981, 15));
        clientReconciles(runId);

        conductor.tick();

        var report = reportOf(runId);
        assertThat(report.get("result")).isEqualTo("BREAKS");
        assertThat(report.get("bankViewsAgree")).isEqualTo(false);
        assertThat((List<?>) report.get("reasons")).isEmpty();
    }

    @Test
    void a_drain_past_its_deadline_ends_incomplete_even_while_it_progresses() {
        var runId = draining();
        bankWithBooks(runId, books(runId, 700, 10));
        clientReconciles(runId);
        backdate(runId, "drain_started_at", props.runs().drainTimeout());

        conductor.tick();

        assertThat(column(runId, "result")).isEqualTo("INCOMPLETE");
    }

    @Test
    @DisplayName("a lost client leaves the bank's verdict and says the client side is missing")
    void a_lost_client_leaves_the_banks_verdict_and_says_the_client_side_is_missing() {
        var runId = draining(true, true);
        bank(runId);

        conductor.tick();

        assertThat(generator.callsTo("GET", forRun(GeneratorApi.RECONCILIATION_PATH, runId))).isEmpty();
        var report = reportOf(runId);
        assertThat(report.get("result")).isEqualTo("INCOMPLETE");
        assertThat(report.get("reasons")).isEqualTo(List.of("client lost"));
        assertThat(report.get("bankViewsAgree")).isEqualTo(true);
    }

    @Test
    @DisplayName("a client reconciliation that cannot succeed ends incomplete with the generator's words")
    void a_client_reconciliation_that_cannot_succeed_ends_incomplete_with_the_generators_words() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId, 404, Map.of("error", "journal released"));

        conductor.tick();

        assertThat(reportOf(runId).get("reasons")).isEqualTo(List.of("client reconciliation failed: journal released"));
    }

    @Test
    void clients_still_stopping_when_the_bank_has_drained_are_asked_again_on_the_next_tick() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId, 409, Map.of("error", GeneratorApi.RUN_STILL_ACTIVE));

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("DRAINING");

        clientReconciles(runId);
        conductor.tick();
        assertThat(column(runId, "result")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("clients still stopping at the drain deadline end incomplete with the generator's words")
    void clients_still_stopping_at_the_drain_deadline_end_incomplete_with_the_generators_words() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId, 409, Map.of("error", GeneratorApi.RUN_STILL_ACTIVE));
        backdate(runId, "drain_started_at", props.runs().drainTimeout());

        conductor.tick();

        assertThat(reportOf(runId).get("reasons")).isEqualTo(List.of("client reconciliation failed: run still active"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a reconciled run's journal is released once, and a generator that cannot release it is only logged")
    void a_reconciled_runs_journal_is_released_once_and_a_generator_that_cannot_release_it_is_only_logged(CapturedOutput output) {
        var released = draining();
        var failing = draining();
        List.of(released, failing).forEach(it -> {
            bank(it);
            clientReconciles(it);
        });
        generator.answer("DELETE", forRun(GeneratorApi.JOURNAL_PATH, failing), 500);

        conductor.tick();
        conductor.tick();

        List.of(released, failing).forEach(runId -> {
            assertThat(stateOf(runId)).isEqualTo("RECONCILED");
            assertThat(generator.callsTo("DELETE", forRun(GeneratorApi.JOURNAL_PATH, runId))).hasSize(1);
        });
        assertThat(output.getOut()).contains("Run " + failing + ": journal not released").doesNotContain("Run " + released + ": journal not released");
    }

    @Test
    @DisplayName("a report stored before the refusal count existed is still read, with none refused")
    void a_report_stored_before_the_refusal_count_existed_is_still_read_with_none_refused() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId);
        conductor.tick();
        jdbc.sql("UPDATE runs SET report = report #- '{client,refused}' WHERE run_id = :id").param("id", runId).update();

        assertThat(((Map<?, ?>) reportOf(runId).get("client")).get("refused")).isEqualTo(0);
    }

    @Test
    @DisplayName("a report stored before notes and chaos existed is still read, with none of either")
    void a_report_stored_before_notes_and_chaos_existed_is_still_read_with_none_of_either() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId);
        conductor.tick();
        jdbc.sql("UPDATE runs SET report = report - 'notes' - 'chaos' WHERE run_id = :id").param("id", runId).update();

        assertThat((List<?>) reportOf(runId).get("notes")).isEmpty();
        assertThat((List<?>) reportOf(runId).get("chaos")).isEmpty();
    }

    @Test
    @DisplayName("the report carries the transfers the gateway refused as busy, from the client reconciliation")
    void the_report_carries_the_transfers_the_gateway_refused_as_busy_from_the_client_reconciliation() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId, 200, clientReport(runId, true, true, 7));

        conductor.tick();

        assertThat(((Map<?, ?>) reportOf(runId).get("client")).get("refused")).isEqualTo(7);
    }

    @Test
    void an_inconsistent_statement_ends_incomplete_at_once() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId, 502, Map.of("error", "statement incomplete"));

        conductor.tick();

        assertThat(reportOf(runId).get("reasons")).isEqualTo(List.of("client reconciliation failed: statement incomplete"));
    }

    @Test
    void an_unavailable_client_reconciliation_is_retried_until_the_drain_deadline() {
        var runId = draining();
        bank(runId);
        clientReconciles(runId, 503, Map.of("error", "gateway unavailable"));

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("DRAINING");

        backdate(runId, "drain_started_at", props.runs().drainTimeout());
        conductor.tick();
        assertThat(reportOf(runId).get("reasons")).isEqualTo(List.of("client reconciliation unavailable"));
    }

    @Test
    void a_bank_that_does_not_answer_until_the_deadline_fails_the_run_without_a_verdict() {
        var runId = draining();
        gateway.answer("GET", forRun(GatewayApi.SUMMARY_PATH, runId), 200, summary(runId));
        ledger.answer("GET", forRun(LedgerApi.RECONCILIATION_PATH, runId), 503);

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("DRAINING");

        backdate(runId, "drain_started_at", props.runs().drainTimeout());
        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("FAILED");
        assertThat(column(runId, "failure")).isEqualTo("bank unavailable");
        assertThat(column(runId, "result")).isNull();
    }

    @Test
    @DisplayName("a projection behind books that are final keeps the drain waiting, and the report comes once it caught up")
    void a_projection_behind_books_that_are_final_keeps_the_drain_waiting_and_the_report_comes_once_it_caught_up() {
        var runId = draining();
        bankWithStats(runId, stats(runId, 990, 975, 15));
        clientReconciles(runId);

        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("DRAINING");
        assertThat(generator.callsTo("GET", forRun(GeneratorApi.RECONCILIATION_PATH, runId))).isEmpty();

        reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 200, stats(runId));
        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RECONCILED");
        var report = reportOf(runId);
        assertThat(report.get("result")).isEqualTo("BALANCED");
        assertThat(report.get("projectionMatchesBooks")).isEqualTo(true);
        assertThat((List<?>) report.get("notes")).isEmpty();
        assertThat(((Map<?, ?>) ((Map<?, ?>) report.get("stats")).get("totals")).get("accepted")).isEqualTo(995);
    }

    @Test
    @DisplayName("the books are marked final once, when the drain first finds them final, and the mark survives a restart")
    void the_books_are_marked_final_once_when_the_drain_first_finds_them_final_and_the_mark_survives_a_restart() {
        var runId = draining();
        bankWithStats(runId, stats(runId, 990, 975, 15));
        clientReconciles(runId);

        conductor.tick();
        var first = column(runId, "books_final_at");
        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("DRAINING");
        assertThat(first).isNotNull();
        assertThat(column(runId, "books_final_at")).isEqualTo(first);
        var sessionId = jdbc.sql("SELECT session_id FROM runs WHERE run_id = :id").param("id", runId).query(UUID.class).single();
        assertThat(Instant.parse((String) getRun(sessionId, runId).getBody().get("booksFinalAt")))
            .isEqualTo(((Timestamp) first).toInstant());
    }

    @Test
    void books_not_final_yet_are_not_marked() {
        var runId = draining();
        bankWithBooks(runId, books(runId, 900, RunFixtures.REJECTED));

        conductor.tick();

        assertThat(column(runId, "books_final_at")).isNull();
    }

    @Test
    @DisplayName("results of every transfer without all their commands are not caught up yet, and the verdict stays the books'")
    void results_of_every_transfer_without_all_their_commands_are_not_caught_up_yet_and_the_verdict_stays_the_books() {
        var runId = draining();
        bankWithStats(runId, stats(runId, RunFixtures.ACCEPTED, RunFixtures.POSTED, RunFixtures.REJECTED, 990));
        clientReconciles(runId);

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("DRAINING");

        backdate(runId, "drain_progress_at", props.reporting().catchUpWait());
        conductor.tick();

        var report = reportOf(runId);
        assertThat(report.get("result")).isEqualTo("BALANCED");
        assertThat((List<?>) report.get("reasons")).isEmpty();
        assertThat(report.get("projectionMatchesBooks")).isEqualTo(false);
        assertThat(report.get("notes")).isEqualTo(List.of("projection does not match the books: commands 990 of 995, posted 980 of 980, rejected 15 of 15"));
    }

    @Test
    @DisplayName("a reporting service that does not answer is waited for as long, then noted without stats")
    void a_reporting_service_that_does_not_answer_is_waited_for_as_long_then_noted_without_stats() {
        var runId = draining();
        bankWithStats(runId, null);
        reporting.status = 503;
        clientReconciles(runId);

        conductor.tick();
        assertThat(stateOf(runId)).isEqualTo("DRAINING");

        backdate(runId, "drain_progress_at", props.reporting().catchUpWait());
        conductor.tick();

        var report = reportOf(runId);
        assertThat(report.get("result")).isEqualTo("BALANCED");
        assertThat(report.get("stats")).isNull();
        assertThat(report.get("projectionMatchesBooks")).isNull();
        assertThat(report.get("notes")).isEqualTo(List.of("reporting unavailable: no numbers from the bank's events"));
    }

    @Test
    void a_drain_that_ends_without_final_books_does_not_wait_for_the_projection() {
        var runId = draining();
        bank(runId, summary(runId), books(runId, 700, 10), null);
        reporting.status = 503;
        clientReconciles(runId);
        conductor.tick();

        backdate(runId, "drain_progress_at", props.runs().drainStallTimeout());
        conductor.tick();

        assertThat(stateOf(runId)).isEqualTo("RECONCILED");
        assertThat(reportOf(runId).get("notes")).isEqualTo(List.of("reporting unavailable: no numbers from the bank's events"));
    }

    @Test
    @DisplayName("the report carries the clients' exact latency and the ledger's duplicates seen while the run was live")
    void the_report_carries_the_clients_exact_latency_and_the_ledgers_duplicates_seen_while_the_run_was_live() {
        var runId = draining();
        bankWithStats(runId, stats(runId, 990, 975, 15));
        clientReconciles(runId);
        var withLatency = simulation(runId, "FINISHED");
        withLatency.putAll(Map.of("waitedLatencyP50Ms", 101.5, "waitedLatencyP99Ms", 870.0));
        generator.answer(
            "GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200,
            withLatency);
        prometheus.answerQuery("GET", PROMETHEUS_QUERY, "query=sum by (run) (ledger_duplicates_total)", 200, duplicates(runId, 7));
        // Live numbers are read beside the steps of the same tick, so the report takes those of an earlier tick.
        conductor.tick();
        prometheus.answerQuery("GET", PROMETHEUS_QUERY, "query=sum by (run) (ledger_duplicates_total)", 200, duplicates(runId, 5));
        reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 200, stats(runId));
        conductor.tick();

        var report = reportOf(runId);
        assertThat(report.get("clientLatencyP50Ms")).isEqualTo(101.5);
        assertThat(report.get("clientLatencyP99Ms")).isEqualTo(870.0);
        assertThat(report.get("ledgerDuplicatesAtLeast")).isEqualTo(7);
    }
}
