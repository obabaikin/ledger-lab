package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.bank.Statement;
import com.ledgerlab.generator.bank.StatementLine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class SimulationControllerTest {

    private static final Duration LONG_RETENTION = Duration.ofMinutes(10);

    private static SimulationController controller(FakeGateway gateway) {
        return controller(gateway, new JournalProperties(LONG_RETENTION, 1_000));
    }

    private static SimulationController controller(FakeGateway gateway, JournalProperties journal) {
        var props = TestData.properties(gateway.url(), 3, Duration.ofMillis(50), journal);
        return new SimulationController(new GatewayClient(props, new ObjectMapper(), new SimpleMeterRegistry()), props, new SimpleMeterRegistry());
    }

    private static StartSimulationRequest request(int tps, int seconds) {
        return StartSimulationRequest.withDefaults(UUID.randomUUID(), tps, seconds, 10, null, null, null, null);
    }

    private static UUID finished(SimulationController controller) {
        return finished(controller, 30);
    }

    private static UUID finished(SimulationController controller, int tps) {
        var run = controller.start(request(tps, 1));
        awaitFinished(controller, run.runId());
        return run.runId();
    }

    private static void awaitFinished(SimulationController controller, UUID runId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> controller.status(runId).state() == SimulationState.FINISHED);
    }

    @Test
    @DisplayName("a start that does not fit next to the journals in memory is refused, not squeezed in")
    void a_start_that_does_not_fit_next_to_the_journals_in_memory_is_refused_not_squeezed_in() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway, new JournalProperties(LONG_RETENTION, 100));
            var first = controller.start(request(60, 1));

            assertThatThrownBy(() -> controller.start(request(50, 1))).isInstanceOf(JournalCapacityException.class);
            assertThatThrownBy(() -> controller.start(request(101, 1))).isInstanceOf(IllegalArgumentException.class);
            assertThat(controller.start(request(40, 1)).state()).isEqualTo(SimulationState.RUNNING);
            awaitFinished(controller, first.runId());
        }
    }

    @Test
    @DisplayName("a repeated start of an active run is told it is running, not that memory is full")
    void a_repeated_start_of_an_active_run_is_told_it_is_running_not_that_memory_is_full() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway, new JournalProperties(LONG_RETENTION, 100));
            var run = request(100, 1);
            controller.start(run);

            assertThatThrownBy(() -> controller.start(run)).isInstanceOf(SimulationAlreadyRunningException.class);
            awaitFinished(controller, run.runId());
        }
    }

    @Test
    @DisplayName("a released journal frees its share of the cap, the status stays readable")
    void a_released_journal_frees_its_share_of_the_cap_the_status_stays_readable() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway, new JournalProperties(Duration.ZERO, 100));
            var runId = finished(controller, 80);
            assertThatThrownBy(() -> controller.start(request(80, 1))).isInstanceOf(JournalCapacityException.class);

            controller.evictFinished();

            assertThat(controller.status(runId).requested()).isGreaterThan(0);
            assertThatThrownBy(() -> controller.transfers(runId, null, null, null, 0, 100)).isInstanceOf(NotFoundException.class);
            assertThat(controller.start(request(80, 1)).state()).isEqualTo(SimulationState.RUNNING);
        }
    }

    @Test
    @DisplayName("a journal released on request is gone at once and frees the cap, the status stays")
    void a_journal_released_on_request_is_gone_at_once_and_frees_the_cap_the_status_stays() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway, new JournalProperties(LONG_RETENTION, 100));
            var runId = finished(controller, 80);
            assertThatThrownBy(() -> controller.start(request(80, 1))).isInstanceOf(JournalCapacityException.class);

            controller.releaseJournal(runId);

            assertThat(controller.status(runId).requested()).isGreaterThan(0);
            assertThatThrownBy(() -> controller.transfers(runId, null, null, null, 0, 100)).isInstanceOf(NotFoundException.class);
            assertThat(controller.start(request(80, 1)).state()).isEqualTo(SimulationState.RUNNING);
        }
    }

    @Test
    @DisplayName("over HTTP a release answers 204, again 404, and 409 while the run is active")
    void over_HTTP_a_release_answers_204_again_404_and_409_while_the_run_is_active() throws Exception {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway);
            var finished = finished(controller);
            var active = controller.start(request(10, 10)).runId();
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            var journal = "/internal/simulations/{runId}/journal";

            mvc.perform(MockMvcRequestBuilders.delete(journal, finished)).andExpect(MockMvcResultMatchers.status().isNoContent());
            mvc.perform(MockMvcRequestBuilders.delete(journal, finished)).andExpect(MockMvcResultMatchers.status().isNotFound());
            mvc.perform(MockMvcRequestBuilders.delete(journal, UUID.randomUUID())).andExpect(MockMvcResultMatchers.status().isNotFound());
            mvc.perform(MockMvcRequestBuilders.delete(journal, active)).andExpect(MockMvcResultMatchers.status().isConflict());
            assertThat(controller.transfers(active, null, null, null, 0, 100).next()).as("the journal is still there").isNotNull();
            controller.stop(active);
        }
    }

    @Test
    @DisplayName("an interrupted statement read is the gateway unavailable, and the interrupt is kept")
    void an_interrupted_statement_read_is_the_gateway_unavailable_and_the_interrupt_is_kept() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> controller.reconciliation(runId)).isInstanceOf(GatewayUnavailableException.class);
            } finally {
                assertThat(Thread.interrupted()).isTrue();
            }
        }
    }

    @Test
    void a_finished_run_reconciles_against_every_page_of_the_statement() {
        try (var gateway = FakeGateway.with().statementPage(7).start()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            var report = controller.reconciliation(runId);

            assertThat(report.balanced()).isTrue();
            assertThat(report.statementFinal()).isTrue();
            assertThat(report.journal().requested()).isGreaterThan(7);
            assertThat(report.statement().rows()).isEqualTo(report.journal().acknowledged()).isEqualTo(report.journal().requested());
        }
    }

    @Test
    @DisplayName("the reconciliation reports the transfers refused as busy, as the status counts them")
    void the_reconciliation_reports_the_transfers_refused_as_busy_as_the_status_counts_them() {
        try (var gateway = FakeGateway.with().busyAttempts(1).start()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            var refused = controller.reconciliation(runId).journal().refused();

            assertThat(refused).isEqualTo(controller.status(runId).requested()).isEqualTo(controller.status(runId).refused());
        }
    }

    @Test
    void a_run_that_may_still_send_is_not_reconciled() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway);
            var run = controller.start(request(10, 10));

            assertThatThrownBy(() -> controller.reconciliation(run.runId())).isInstanceOf(RunStillActiveException.class);

            controller.stop(run.runId());
            await().atMost(Duration.ofSeconds(15)).until(() -> !controller.status(run.runId()).state().isActive());
            assertThat(controller.reconciliation(run.runId()).balanced()).isTrue();
        }
    }

    @Test
    void a_period_the_gateway_has_not_closed_is_passed_on_as_not_closed() {
        try (var gateway = FakeGateway.with().periodNotClosed().start()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            assertThatThrownBy(() -> controller.reconciliation(runId))
                .isInstanceOf(PeriodNotClosedException.class)
                .hasMessage(GeneratorErrors.PERIOD_NOT_CLOSED);
        }
    }

    @Test
    @DisplayName("a 5xx on a later page makes the gateway unavailable, a 4xx is a bad answer")
    void a_5xx_on_a_later_page_makes_the_gateway_unavailable_a_4xx_is_a_bad_answer() {
        try (var gateway = FakeGateway.with().statementPage(7).statementFailure(page -> page == 2 ? 503 : null).start()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            assertThatThrownBy(() -> controller.reconciliation(runId)).isInstanceOf(GatewayUnavailableException.class);
            assertThat(controller.reconciliation(runId).balanced()).isTrue();
        }
        try (var gateway = FakeGateway.with().statementFailure(page -> 400).start()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            assertThatThrownBy(() -> controller.reconciliation(runId)).isInstanceOf(BadGatewayAnswerException.class);
        }
    }

    @Test
    void a_finished_run_started_again_under_its_id_does_not_count_against_the_cap_twice() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway, new JournalProperties(LONG_RETENTION, 100));
            var run = request(80, 1);
            controller.start(run);
            awaitFinished(controller, run.runId());

            assertThat(controller.start(run).state()).isEqualTo(SimulationState.RUNNING);
        }
    }

    @Test
    void an_unreadable_statement_page_is_a_bad_answer() {
        var bodies = List.of(
            "null",
            "not json",
            "{\"runId\":\"" + UUID.randomUUID() + "\",\"closedAt\":\"" + Instant.now() + "\",\"entryCount\":1,\"items\":[null],\"next\":null}");
        for (var body : bodies) {
            try (var gateway = FakeGateway.with().rawStatement(body).start()) {
                var controller = controller(gateway);
                var runId = finished(controller);

                assertThatThrownBy(() -> controller.reconciliation(runId)).as(body).isInstanceOf(BadGatewayAnswerException.class);
            }
        }
    }

    @Test
    void a_gateway_that_cannot_be_reached_for_the_statement_is_unavailable() {
        var gateway = new FakeGateway();
        var controller = controller(gateway);
        var runId = finished(controller);

        gateway.close();

        assertThatThrownBy(() -> controller.reconciliation(runId)).isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void a_released_journal_cannot_be_reconciled() {
        try (var gateway = new FakeGateway()) {
            var controller = controller(gateway, new JournalProperties(Duration.ZERO, 1_000));
            var runId = finished(controller);

            controller.evictFinished();

            assertThatThrownBy(() -> controller.reconciliation(runId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("released");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("a statement that pages back to a cursor it gave before is a bad answer, not an endless loop")
    void a_statement_that_pages_back_to_a_cursor_it_gave_before_is_a_bad_answer_not_an_endless_loop() {
        try (var gateway = FakeGateway.with().rawStatement("{\"entryCount\":0,\"items\":[],\"next\":\"" + UUID.randomUUID() + "\"}").start()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            assertThatThrownBy(() -> controller.reconciliation(runId))
                .isInstanceOf(BadGatewayAnswerException.class)
                .hasMessageContaining("pages back");
        }
    }

    private static void assertBadStatement(BiFunction<Integer, Statement, Statement> tamper, String message) {
        try (var gateway = FakeGateway.with().statementPage(7).tamperStatement(tamper).start()) {
            var controller = controller(gateway);
            var runId = finished(controller);

            assertThatThrownBy(() -> controller.reconciliation(runId))
                .isInstanceOf(BadGatewayAnswerException.class)
                .hasMessageContaining(message);
        }
    }

    private static Statement withItems(Statement statement, List<StatementLine> items) {
        return new Statement(statement.entryCount(), items, statement.next());
    }

    private static Statement withEntryCount(Statement statement, long entryCount) {
        return new Statement(entryCount, statement.items(), statement.next());
    }

    @Test
    @DisplayName("a line repeated across a page boundary is a bad answer, also when it takes the place of a lost one")
    void a_line_repeated_across_a_page_boundary_is_a_bad_answer_also_when_it_takes_the_place_of_a_lost_one() {
        for (var dropped : List.of(0, 1)) {
            var lastOfFirstPage = new AtomicReference<StatementLine>();
            assertBadStatement((page, statement) -> {
                if (page == 1) {
                    lastOfFirstPage.set(statement.items().getLast());
                }
                if (page != 2) {
                    return statement;
                }
                var items = new ArrayList<StatementLine>();
                items.add(lastOfFirstPage.get());
                items.addAll(statement.items().subList(dropped, statement.items().size()));
                return withItems(statement, items);
            }, "twice");
        }
    }

    @Test
    void an_entry_count_that_differs_between_pages_is_a_bad_answer() {
        assertBadStatement((page, statement) -> page == 2 ? withEntryCount(statement, statement.entryCount() + 1) : statement, "between pages");
    }

    @Test
    void fewer_or_more_lines_than_the_entry_count_is_an_incomplete_statement() {
        assertBadStatement(
            (page, statement) -> page == 2 ? withItems(statement, statement.items().subList(1, statement.items().size())) : statement,
            "incomplete");
        assertBadStatement((page, statement) -> withEntryCount(statement, statement.entryCount() - 1), "incomplete");
    }

    @Test
    void over_HTTP_an_unavailable_gateway_answers_503_and_a_bad_answer_502() throws Exception {
        var cases = Map.of(
            HttpStatus.SERVICE_UNAVAILABLE, FakeGateway.with().statementFailure(page -> HttpStatus.SERVICE_UNAVAILABLE.value()),
            HttpStatus.BAD_GATEWAY, FakeGateway.with().rawStatement("not json"));
        for (var expected : cases.entrySet()) {
            try (var gateway = expected.getValue().start()) {
                var controller = controller(gateway);
                var runId = finished(controller);

                MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(MockMvcRequestBuilders.get("/internal/simulations/{runId}/reconciliation", runId))
                    .andExpect(MockMvcResultMatchers.status().is(expected.getKey().value()));
            }
        }
    }
}
