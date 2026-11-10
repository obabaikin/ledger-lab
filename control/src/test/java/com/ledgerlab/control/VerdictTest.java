package com.ledgerlab.control;

import static com.ledgerlab.control.runs.Verdicts.projectionMatchesBooks;
import static com.ledgerlab.control.runs.Verdicts.verdictOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.RunSummary;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.ledger.ReconciliationReport;
import com.ledgerlab.control.reporting.RunStats;
import com.ledgerlab.control.runs.ClientSide;
import com.ledgerlab.control.runs.RunResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VerdictTest {

    private final UUID runId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

    private <T> T read(Object value, Class<T> type) {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }

    private RunSummary summary() {
        return summary(RunFixtures.POSTED, RunFixtures.REJECTED);
    }

    private RunSummary summary(long posted, long rejected) {
        return summary(posted, rejected, RunFixtures.REASONS);
    }

    private RunSummary summary(Map<String, Long> reasons) {
        return summary(RunFixtures.POSTED, RunFixtures.REJECTED, reasons);
    }

    private RunSummary summary(long posted, long rejected, Map<String, Long> reasons) {
        return read(RunFixtures.summary(runId, RunFixtures.ACCEPTED, posted, rejected, reasons), RunSummary.class);
    }

    private ReconciliationReport books() {
        return books(true);
    }

    private ReconciliationReport books(boolean balanced) {
        return read(
            RunFixtures.books(runId, RunFixtures.POSTED, RunFixtures.REJECTED, balanced, RunFixtures.ACCOUNTS, RunFixtures.REASONS),
            ReconciliationReport.class);
    }

    private RunStats stats() {
        return stats(RunFixtures.POSTED, RunFixtures.REJECTED);
    }

    private RunStats stats(long posted, long rejected) {
        return read(RunFixtures.stats(runId, RunFixtures.ACCEPTED, posted, rejected), RunStats.class);
    }

    private ClientSide client() {
        return client(true, true);
    }

    private ClientSide client(boolean balanced, boolean statementFinal) {
        return new ClientSide.Reconciled(read(RunFixtures.clientReport(runId, balanced, statementFinal), ClientReconciliationReport.class));
    }

    @Test
    void all_three_sides_agreeing_on_a_finished_drain_is_balanced() {
        assertThat(verdictOf(summary(), books(), client()).result()).isEqualTo(RunResult.BALANCED);
    }

    @Test
    void unbalanced_books_are_a_break() {
        assertThat(verdictOf(summary(), books(false), client()).result()).isEqualTo(RunResult.BREAKS);
    }

    @Test
    void gateway_and_ledger_telling_different_outcomes_is_a_break() {
        var report = verdictOf(summary(981, 14), books(), client());

        assertThat(report.result()).isEqualTo(RunResult.BREAKS);
        assertThat(report.bankViewsAgree()).isFalse();
    }

    @Test
    void gateway_and_ledger_giving_different_reasons_is_a_break() {
        var report = verdictOf(summary(Map.of("INSUFFICIENT_FUNDS", 15L)), books(), client());

        assertThat(report.result()).isEqualTo(RunResult.BREAKS);
    }

    @Test
    void a_client_break_is_a_break() {
        assertThat(verdictOf(summary(), books(), client(false, true)).result()).isEqualTo(RunResult.BREAKS);
    }

    @Test
    void a_statement_not_final_makes_the_verdict_incomplete() {
        var report = verdictOf(summary(), books(), client(true, false));

        assertThat(report.result()).isEqualTo(RunResult.INCOMPLETE);
        assertThat(report.reasons()).containsExactly("statement not final");
    }

    @Test
    @DisplayName("a gateway that knows more results than it accepted is a break, not lag")
    void a_gateway_that_knows_more_results_than_it_accepted_is_a_break_not_lag() {
        assertThat(verdictOf(summary(981, RunFixtures.REJECTED), books(), client()).result()).isEqualTo(RunResult.BREAKS);
    }

    @Test
    void a_break_outweighs_an_incomplete_side() {
        var report = verdictOf(summary(), books(false), new ClientSide.Missing("client lost"));

        assertThat(report.result()).isEqualTo(RunResult.BREAKS);
        assertThat(report.reasons()).containsExactly("client lost");
    }

    @Test
    @DisplayName("the report keeps the bank's latency at the end of the run")
    void the_report_keeps_the_banks_latency_at_the_end_of_the_run() {
        var report = verdictOf(summary(), books(), client());

        assertThat(report.bankLatencyP50Ms()).isEqualTo(12.0);
        assertThat(report.bankLatencyP99Ms()).isEqualTo(480.5);
    }

    @Test
    @DisplayName("rejections by reason are the ledger's")
    void rejections_by_reason_are_the_ledgers() {
        var report = verdictOf(summary(Map.of("INSUFFICIENT_FUNDS", 15L)), books(), client());

        assertThat(report.rejectionsByReason()).isEqualTo(Map.of("BLOCKED_ACCOUNT", 15L));
    }

    @Test
    @DisplayName("a projection that differs from the books only in posted, or only in rejected, does not match them")
    void a_projection_that_differs_from_the_books_only_in_posted_or_only_in_rejected_does_not_match_them() {
        assertThat(projectionMatchesBooks(stats(), summary(), books())).isTrue();
        assertThat(projectionMatchesBooks(stats(979, RunFixtures.REJECTED), summary(), books())).isFalse();
        assertThat(projectionMatchesBooks(stats(RunFixtures.POSTED, 14), summary(), books())).isFalse();
    }
}
