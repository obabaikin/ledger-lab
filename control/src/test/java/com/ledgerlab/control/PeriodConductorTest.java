package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.ACCEPTED;
import static com.ledgerlab.control.Fixtures.POSTED;
import static com.ledgerlab.control.Fixtures.POSTED_AMOUNT_CENTS;
import static com.ledgerlab.control.Fixtures.REASONS;
import static com.ledgerlab.control.Fixtures.REJECTED;
import static com.ledgerlab.control.Fixtures.check;
import static com.ledgerlab.control.Fixtures.clientDone;
import static com.ledgerlab.control.Fixtures.clientReconciliation;
import static com.ledgerlab.control.Fixtures.clientReport;
import static com.ledgerlab.control.Fixtures.closed;
import static com.ledgerlab.control.Fixtures.mapOf;
import static com.ledgerlab.control.Fixtures.reconciliation;
import static com.ledgerlab.control.Fixtures.stats;
import static com.ledgerlab.control.Fixtures.summary;
import static com.ledgerlab.control.http.ServiceHttp.forPeriod;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.reporting.ReportingApi;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PeriodConductorTest extends BankIntegrationTest {

    private static final Instant THIRD_PERIOD = NEXT_PERIOD.plus(LENGTH);
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DRAIN_STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration REQUEST_RETRY = Duration.ofSeconds(30);
    private static final Duration CATCH_UP_WAIT = Duration.ofSeconds(60);
    private static final Duration DETAILS_RETENTION = Duration.ofHours(4);

    private int closeCalls(Instant period) {
        return gateway.callsTo("POST", forPeriod(GatewayApi.CLOSE_PATH, period)).size();
    }

    private int checkRequests(Instant period) {
        return ledger.callsTo("POST", forPeriod(LedgerApi.CHECK_PATH, period)).size();
    }

    private int clientRequests(Instant period) {
        return generator.callsTo("POST", forPeriod(GeneratorApi.RECONCILIATION_PATH, period)).size();
    }

    private Map<String, Object> periodView(Instant period) {
        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) periodFromApi(period).get("period");
        return view;
    }

    @Test
    @DisplayName("a period is closed a minute after its end, not before, and only once")
    void a_period_is_closed_a_minute_after_its_end_not_before_and_only_once() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);

        conductor.tick(closingTimeOf(PERIOD).minusSeconds(1));
        assertThat(closeCalls(PERIOD)).isZero();
        assertThat(stateOf(PERIOD)).isEqualTo("OPEN");

        conductor.tick(closingTimeOf(PERIOD));
        assertThat(closeCalls(PERIOD)).isEqualTo(1);
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(closingTimeOf(PERIOD).plusSeconds(1));
        conductor.tick(closingTimeOf(PERIOD).plusSeconds(2));
        assertThat(stateOf(PERIOD)).isEqualTo("RECONCILED");
        assertThat(closeCalls(PERIOD)).isEqualTo(1);
    }

    @Test
    @DisplayName("a period whose numbers agree is reconciled as balanced, and its report keeps what the verdict rests on")
    void a_period_whose_numbers_agree_is_reconciled_as_balanced_and_its_report_keeps_what_the_verdict_rests_on() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);

        closeAndReconcile(PERIOD);

        var view = periodView(PERIOD);
        assertThat(view.get("state")).isEqualTo("RECONCILED");
        assertThat(view.get("verdict")).isEqualTo("BALANCED");
        assertThat(view.get("failure")).isNull();
        assertThat(view.get("closedAt")).isEqualTo(PERIOD.plusSeconds(3660).toString());
        assertThat(view.get("numbers")).isEqualTo(mapOf(
            "accepted", (int) ACCEPTED, "posted", (int) POSTED, "rejected", (int) REJECTED, "postedAmountCents", (int) POSTED_AMOUNT_CENTS,
            "rejectionsByReason", Map.of("INSUFFICIENT_FUNDS", 60, "BLOCKED_ACCOUNT", 40)));
        var report = reportOf(PERIOD);
        assertThat(report.get("verdict")).isEqualTo("BALANCED");
        assertThat(report.get("mismatches")).isEqualTo(List.of());
        assertThat(report.get("gateway")).isEqualTo(mapOf(
            "accepted", (int) ACCEPTED, "published", (int) ACCEPTED, "posted", (int) POSTED, "rejected", (int) REJECTED, "entryCount", (int) ACCEPTED));
        assertThat(report.get("ledger")).isEqualTo(mapOf("postedTransfers", (int) POSTED, "rejectedTransfers", (int) REJECTED, "poolPostings", 70));
        assertThat(((Map<?, ?>) report.get("ledgerCheck")).get("balanced")).isEqualTo(true);
        assertThat(((Map<?, ?>) report.get("books")).get("accounts")).isEqualTo((int) Fixtures.ACCOUNTS);
        assertThat(((Map<?, ?>) report.get("client")).get("balanced")).isEqualTo(true);
        assertThat(((Map<?, ?>) report.get("stats")).get("commandsSeen")).isEqualTo((int) ACCEPTED);
        assertThat(report.get("notes")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("a transfer the clients were told of and the statement lacks makes the period a mismatch whose report names the transfer")
    void a_transfer_the_clients_were_told_of_and_the_statement_lacks_makes_the_period_a_mismatch_whose_report_names_the_transfer() {
        var lost = UUID.randomUUID();
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        generator.answer(
            "GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD), 200, clientDone(PERIOD, clientReport(PERIOD, false, List.of(lost))));

        closeAndReconcile(PERIOD);

        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("MISMATCH");
        var report = reportOf(PERIOD);
        assertThat(report.get("mismatches")).isEqualTo(List.of("CLIENT_JOURNAL_DISAGREES"));
        var breaks = (Map<?, ?>) ((Map<?, ?>) report.get("client")).get("breaks");
        assertThat(breaks.get("acknowledgedNotInStatement")).isEqualTo(mapOf("count", 1, "transferIds", List.of(lost.toString())));
    }

    @Test
    @DisplayName("a period without transfers is closed and balanced with zeros")
    void a_period_without_transfers_is_closed_and_balanced_with_zeros() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("POST", forPeriod(GatewayApi.CLOSE_PATH, PERIOD), 200, closed(PERIOD, 0));
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, 0, 0, 0, Map.of()));
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, 0, 0, Map.of(), check(true)));
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, PERIOD), 200, stats(PERIOD, 0, 0, 0));

        closeAndReconcile(PERIOD);

        var view = periodView(PERIOD);
        assertThat(view.get("verdict")).isEqualTo("BALANCED");
        assertThat(((Map<?, ?>) view.get("numbers")).get("accepted")).isEqualTo(0);
    }

    @Test
    @DisplayName("a bank that does not answer past the limit fails the period with the reason, and the next period is closed and reconciled")
    void a_bank_that_does_not_answer_past_the_limit_fails_the_period_with_the_reason_and_the_next_period_is_closed_and_reconciled() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 503);
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plus(DRAIN_TIMEOUT).minusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(closing.plus(DRAIN_TIMEOUT));
        var failed = periodView(PERIOD);
        assertThat(failed.get("state")).isEqualTo("FAILED");
        assertThat(failed.get("failure")).isEqualTo("BANK_UNAVAILABLE");
        assertThat(failed.get("verdict")).isNull();
        assertThat(reportOf(PERIOD)).isNull();

        bankAnswersFor(NEXT_PERIOD);
        closeAndReconcile(NEXT_PERIOD);
        assertThat(stateOf(NEXT_PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("periods that ended while control was down are opened and reconciled one by one, oldest first")
    void periods_that_ended_while_control_was_down_are_opened_and_reconciled_one_by_one_oldest_first() {
        bankStartedIn(PERIOD);
        List.of(PERIOD, NEXT_PERIOD, THIRD_PERIOD).forEach(this::bankAnswersFor);
        var restarted = closingTimeOf(THIRD_PERIOD);

        conductor.tick(restarted);
        conductor.tick(restarted.plusSeconds(1));
        assertThat(periodsInOrder()).containsExactly("RECONCILED", "OPEN", "OPEN", "OPEN");

        for (var second = 2; second <= 5; second++) {
            conductor.tick(restarted.plusSeconds(second));
        }
        assertThat(periodsInOrder()).containsExactly("RECONCILED", "RECONCILED", "RECONCILED", "OPEN");
        assertThat(gateway.calls.stream().filter(it -> it.method().equals("POST")).map(StubService.Call::path).toList()).containsExactly(
            forPeriod(GatewayApi.CLOSE_PATH, PERIOD), forPeriod(GatewayApi.CLOSE_PATH, NEXT_PERIOD), forPeriod(GatewayApi.CLOSE_PATH, THIRD_PERIOD));
    }

    @Test
    @DisplayName("a period control left half settled when it went down is settled after the restart, with its limit started again")
    void a_period_control_left_half_settled_when_it_went_down_is_settled_after_the_restart_with_its_limit_started_again() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, POSTED, REJECTED, REASONS, null));
        conductor.tick(closingTimeOf(PERIOD));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        controlRestarts();
        var back = closingTimeOf(PERIOD).plus(DRAIN_TIMEOUT).plusSeconds(60);
        conductor.tick(back);
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");
        assertThat(checkRequests(PERIOD)).isEqualTo(1);

        conductor.tick(back.plus(DRAIN_TIMEOUT).minusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD));
        conductor.tick(back.plus(DRAIN_TIMEOUT).minusSeconds(1));
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("a drain that did not move while control was down is given its minute again after the restart")
    void a_drain_that_did_not_move_while_control_was_down_is_given_its_minute_again_after_the_restart() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, ACCEPTED, POSTED - 10, REJECTED, REASONS));
        var closing = closingTimeOf(PERIOD);
        conductor.tick(closing);
        conductor.tick(closing.plusSeconds(1));

        controlRestarts();
        var back = closing.plusSeconds(300);
        conductor.tick(back);
        conductor.tick(back.plus(DRAIN_STALL_TIMEOUT).minusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(back.plus(DRAIN_STALL_TIMEOUT));
        assertThat(periodView(PERIOD).get("failure")).isEqualTo("DRAIN_STALLED");
    }

    @Test
    @DisplayName("a period older than the bank keeps its transfers fails at once, without a call to the bank")
    void a_period_older_than_the_bank_keeps_its_transfers_fails_at_once_without_a_call_to_the_bank() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);

        conductor.tick(PERIOD.plus(DETAILS_RETENTION));

        assertThat(periodView(PERIOD).get("failure")).isEqualTo("DETAILS_EXPIRED");
        assertThat(closeCalls(PERIOD)).isZero();
    }

    @Test
    @DisplayName("a period the gateway does not know fails at once")
    void a_period_the_gateway_does_not_know_fails_at_once() {
        bankStartedIn(PERIOD);
        gateway.answer("POST", forPeriod(GatewayApi.CLOSE_PATH, PERIOD), 404, mapOf("error", "unknown-period"));

        conductor.tick(closingTimeOf(PERIOD));

        assertThat(periodView(PERIOD).get("failure")).isEqualTo("PERIOD_UNKNOWN_TO_BANK");
    }

    @Test
    @DisplayName("the ledger's check is asked for once the period is drained, and again only after a while without it")
    void the_ledgers_check_is_asked_for_once_the_period_is_drained_and_again_only_after_a_while_without_it() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, POSTED, REJECTED, REASONS, null));
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        assertThat(checkRequests(PERIOD)).isZero();
        conductor.tick(closing.plusSeconds(1));
        conductor.tick(closing.plusSeconds(2));
        assertThat(checkRequests(PERIOD)).isEqualTo(1);

        conductor.tick(closing.plusSeconds(1).plus(REQUEST_RETRY));
        assertThat(checkRequests(PERIOD)).isEqualTo(2);
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD));
        conductor.tick(closing.plusSeconds(2).plus(REQUEST_RETRY));
        assertThat(stateOf(PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("the clients' reconciliation is asked for, and again after a while when the generator forgot it or failed at it")
    void the_clients_reconciliation_is_asked_for_and_again_after_a_while_when_the_generator_forgot_it_or_failed_at_it() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        var path = forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD);
        generator.answer("GET", path, 200, clientReconciliation(PERIOD, "NOT_STARTED"));
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plusSeconds(1));
        assertThat(clientRequests(PERIOD)).isEqualTo(1);

        generator.answer("GET", path, 200, clientReconciliation(PERIOD, "RUNNING"));
        conductor.tick(closing.plusSeconds(1).plus(REQUEST_RETRY));
        assertThat(clientRequests(PERIOD)).isEqualTo(1);

        generator.answer("GET", path, 200, mapOf("periodStart", PERIOD, "state", "FAILED", "failure", "GATEWAY_UNAVAILABLE", "report", null));
        conductor.tick(closing.plusSeconds(2).plus(REQUEST_RETRY));
        assertThat(clientRequests(PERIOD)).isEqualTo(2);

        generator.answer("GET", path, 200, clientReconciliation(PERIOD, "DONE"));
        conductor.tick(closing.plusSeconds(3).plus(REQUEST_RETRY));
        assertThat(stateOf(PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("a drain that stops moving for a minute fails the period with its counts, and the ledger is not asked to check books still open")
    void a_drain_that_stops_moving_for_a_minute_fails_the_period_with_its_counts_and_the_ledger_is_not_asked_to_check_books_still_open() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, ACCEPTED, POSTED - 10, REJECTED, REASONS));
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, POSTED - 1, REJECTED, REASONS, null));
        generator.answer("GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD), 200, clientReconciliation(PERIOD, "NOT_STARTED"));
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plusSeconds(1));
        conductor.tick(closing.plusSeconds(1).plus(DRAIN_STALL_TIMEOUT).minusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(closing.plusSeconds(1).plus(DRAIN_STALL_TIMEOUT));
        var view = periodView(PERIOD);
        assertThat(view.get("failure")).isEqualTo("DRAIN_STALLED");
        assertThat(view.get("numbers")).isEqualTo(mapOf(
            "accepted", (int) ACCEPTED, "posted", (int) POSTED - 1, "rejected", (int) REJECTED, "postedAmountCents", (int) POSTED_AMOUNT_CENTS,
            "rejectionsByReason", Map.of("INSUFFICIENT_FUNDS", 60, "BLOCKED_ACCOUNT", 40)));
        assertThat(checkRequests(PERIOD)).isZero();
        assertThat(clientRequests(PERIOD)).isZero();
    }

    @Test
    @DisplayName("more outcomes than accepted transfers fail the period at once: the counts cannot be reconciled")
    void more_outcomes_than_accepted_transfers_fail_the_period_at_once_the_counts_cannot_be_reconciled() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, POSTED + 1, REJECTED, REASONS, null));

        closeAndReconcile(PERIOD);

        assertThat(periodView(PERIOD).get("failure")).isEqualTo("MORE_RESULTS_THAN_ACCEPTED");
        assertThat(checkRequests(PERIOD)).isZero();
    }

    @Test
    @DisplayName("a clients' reconciliation that says it is done and carries no report fails the period at the limit, and the next period goes on")
    void a_clients_reconciliation_that_says_it_is_done_and_carries_no_report_fails_the_period_at_the_limit_and_the_next_period_goes_on() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        generator.answer(
            "GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD), 200, mapOf("periodStart", PERIOD, "state", "DONE", "failure", null, "report", null));
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");
        conductor.tick(closing.plus(DRAIN_TIMEOUT));
        assertThat(periodView(PERIOD).get("failure")).isEqualTo("CLIENT_RECONCILIATION_UNAVAILABLE");

        bankAnswersFor(NEXT_PERIOD);
        closeAndReconcile(NEXT_PERIOD);
        assertThat(stateOf(NEXT_PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("a drain that moves again is waited for")
    void a_drain_that_moves_again_is_waited_for() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, ACCEPTED, POSTED - 10, REJECTED, REASONS));
        var closing = closingTimeOf(PERIOD);
        conductor.tick(closing);
        conductor.tick(closing.plusSeconds(1));

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, ACCEPTED, POSTED - 5, REJECTED, REASONS));
        conductor.tick(closing.plus(DRAIN_STALL_TIMEOUT));
        conductor.tick(closing.plusSeconds(1).plus(DRAIN_STALL_TIMEOUT));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD));
        conductor.tick(closing.plusSeconds(2).plus(DRAIN_STALL_TIMEOUT));
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("a drain that still moves at the limit fails as a drain timed out and keeps its counts")
    void a_drain_that_still_moves_at_the_limit_fails_as_a_drain_timed_out_and_keeps_its_counts() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, ACCEPTED, POSTED - 10, REJECTED, REASONS));
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plus(DRAIN_TIMEOUT).minusSeconds(30));
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, ACCEPTED, POSTED - 5, REJECTED, REASONS));
        conductor.tick(closing.plus(DRAIN_TIMEOUT));

        var view = periodView(PERIOD);
        assertThat(view.get("failure")).isEqualTo("DRAIN_TIMED_OUT");
        assertThat(((Map<?, ?>) view.get("numbers")).get("accepted")).isEqualTo((int) ACCEPTED);
    }

    @Test
    @DisplayName("a drained period whose check never comes fails at the limit as check unavailable")
    void a_drained_period_whose_check_never_comes_fails_at_the_limit_as_check_unavailable() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, POSTED, REJECTED, REASONS, null));
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plus(DRAIN_TIMEOUT));

        assertThat(periodView(PERIOD).get("failure")).isEqualTo("LEDGER_CHECK_UNAVAILABLE");
    }

    @Test
    @DisplayName("results that arrive between the gateway's two reads are not taken for a difference in the reasons")
    void results_that_arrive_between_the_gateways_two_reads_are_not_taken_for_a_difference_in_the_reasons() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer(
            "GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200,
            summary(PERIOD, ACCEPTED, POSTED, REJECTED, Map.of("INSUFFICIENT_FUNDS", 60L, "BLOCKED_ACCOUNT", 39L)));
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD));
        conductor.tick(closing.plusSeconds(2));
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("without the reporting service the period waits for it a while, then is reconciled with a note and no turnover")
    void without_the_reporting_service_the_period_waits_for_it_a_while_then_is_reconciled_with_a_note_and_no_turnover() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, PERIOD), 503);
        var closing = closingTimeOf(PERIOD);

        conductor.tick(closing);
        conductor.tick(closing.plusSeconds(1));
        conductor.tick(closing.plusSeconds(1).plus(CATCH_UP_WAIT).minusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(closing.plusSeconds(1).plus(CATCH_UP_WAIT));
        var view = periodView(PERIOD);
        assertThat(view.get("verdict")).isEqualTo("BALANCED");
        assertThat(((Map<?, ?>) view.get("numbers")).get("postedAmountCents")).isEqualTo(0);
        assertThat(reportOf(PERIOD).get("notes")).isEqualTo(List.of("REPORTING_UNAVAILABLE"));
        assertThat(reportOf(PERIOD).get("stats")).isNull();
    }

    @Test
    @DisplayName("a crash of the ledger is in the report of the period it fell in, not in the next one's")
    void a_crash_of_the_ledger_is_in_the_report_of_the_period_it_fell_in_not_in_the_next_ones() {
        bankStartedIn(PERIOD);
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (:at, ARRAY['ledger-0', 'ledger-1'])")
            .param("at", Timestamp.from(PERIOD.plusSeconds(1200)))
            .update();
        List.of(PERIOD, NEXT_PERIOD).forEach(this::bankAnswersFor);

        closeAndReconcile(PERIOD);
        closeAndReconcile(NEXT_PERIOD);

        assertThat(reportOf(PERIOD).get("chaos"))
            .isEqualTo(List.of(mapOf("at", PERIOD.plusSeconds(1200).toString(), "instances", List.of("ledger-0", "ledger-1"))));
        assertThat(reportOf(NEXT_PERIOD).get("chaos")).isEqualTo(List.of());
    }
}
