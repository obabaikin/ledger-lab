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

import com.ledgerlab.control.availability.AvailabilityRepository;
import com.ledgerlab.control.chaos.ChaosRepository;
import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.gateway.GatewayClient;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.periods.PeriodCall;
import com.ledgerlab.control.periods.PeriodConductor;
import com.ledgerlab.control.periods.PeriodRepository;
import com.ledgerlab.control.reporting.ReportingApi;
import com.ledgerlab.control.reporting.ReportingClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PeriodConductorTest extends BankIntegrationTest {

    private static final Instant THIRD_PERIOD = NEXT_PERIOD.plus(LENGTH);
    private static final Duration DETAILS_RETENTION = Duration.ofHours(4);
    /** Fine enough to see a call made too early, coarse enough to keep a period's closing short. */
    private static final Duration STEP = Duration.ofSeconds(5);

    @Autowired PeriodRepository periodRepository;
    @Autowired LedgerClient ledgerClient;
    @Autowired GatewayClient gatewayClient;
    @Autowired GeneratorClient generatorClient;
    @Autowired ReportingClient reportingClient;
    @Autowired ChaosRepository chaosRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired DatabaseClock databaseClock;

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

    private void checkNotMade(Instant period) {
        ledger.answer("GET", forPeriod(LedgerApi.CHECK_PATH, period), 200, mapOf("check", null));
    }

    private void notDrained(Instant period, long posted) {
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, period), 200, summary(period, ACCEPTED, posted, REJECTED, REASONS));
    }

    /** Every action from the close to the deadline, each when it is due. */
    private void tickUpToTheDeadline(Instant period) {
        conductor.tick(closingTimeOf(period));
        ticks(countTimeOf(period), deadlineOf(period), RETRY_WAIT);
        conductor.tick(deadlineOf(period).minusSeconds(1));
    }

    private void failsAtTheDeadlineWith(String failure) {
        tickUpToTheDeadline(PERIOD);
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(deadlineOf(PERIOD));

        assertThat(periodView(PERIOD).get("failure")).isEqualTo(failure);
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

        conductor.tick(countTimeOf(PERIOD));
        conductor.tick(askTimeOf(PERIOD));
        assertThat(stateOf(PERIOD)).isEqualTo("RECONCILED");
        assertThat(closeCalls(PERIOD)).isEqualTo(1);
    }

    @Test
    @DisplayName("until its counts are due nothing is asked about a closed period, and then the gateway, the ledger and reporting once each")
    void until_its_counts_are_due_nothing_is_asked_about_a_closed_period_and_then_the_gateway_the_ledger_and_reporting_once_each() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);

        ticks(closingTimeOf(PERIOD), countTimeOf(PERIOD), STEP);

        assertThat(calls(PeriodCall.GATEWAY_CLOSE)).isEqualTo(1);
        for (var call : PeriodCall.values()) {
            if (call != PeriodCall.GATEWAY_CLOSE) {
                assertThat(calls(call)).as(call.tag()).isZero();
            }
        }

        conductor.tick(countTimeOf(PERIOD));
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_RECONCILIATION)).isEqualTo(1);
        assertThat(calls(PeriodCall.REPORTING_STATS)).isEqualTo(1);
    }

    @Test
    @DisplayName("counts that do not agree yet are read again after the wait, and not in between")
    void counts_that_do_not_agree_yet_are_read_again_after_the_wait_and_not_in_between() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        notDrained(PERIOD, POSTED - 10);
        conductor.tick(closingTimeOf(PERIOD));

        ticks(countTimeOf(PERIOD), countTimeOf(PERIOD).plus(RETRY_WAIT), STEP);
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_RECONCILIATION)).isEqualTo(1);
        assertThat(checkRequests(PERIOD)).isZero();

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD));
        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT));
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isEqualTo(2);
        assertThat(calls(PeriodCall.LEDGER_RECONCILIATION)).isEqualTo(2);
        assertThat(checkRequests(PERIOD)).isEqualTo(1);
    }

    @Test
    @DisplayName("once the counts agree, the check and the clients' reconciliation start at once and are asked about after the wait, then every wait")
    void once_the_counts_agree_the_check_and_the_clients_reconciliation_start_at_once_and_are_asked_about_after_the_wait_then_every_wait() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        checkNotMade(PERIOD);
        generator.answer("GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD), 200, clientReconciliation(PERIOD, "RUNNING"));
        conductor.tick(closingTimeOf(PERIOD));

        conductor.tick(countTimeOf(PERIOD));
        assertThat(checkRequests(PERIOD)).isEqualTo(1);
        assertThat(clientRequests(PERIOD)).isEqualTo(1);

        ticks(countTimeOf(PERIOD).plus(STEP), askTimeOf(PERIOD), STEP);
        assertThat(calls(PeriodCall.LEDGER_CHECK)).isZero();
        assertThat(calls(PeriodCall.GENERATOR_RECONCILIATION)).isZero();

        ticks(askTimeOf(PERIOD), askTimeOf(PERIOD).plus(RETRY_WAIT), STEP);
        assertThat(calls(PeriodCall.LEDGER_CHECK)).isEqualTo(1);
        assertThat(calls(PeriodCall.GENERATOR_RECONCILIATION)).isEqualTo(1);
        assertThat(clientRequests(PERIOD)).isEqualTo(1);

        ledger.answer("GET", forPeriod(LedgerApi.CHECK_PATH, PERIOD), 200, mapOf("check", check(true)));
        generator.answer("GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD), 200, clientReconciliation(PERIOD, "DONE"));
        conductor.tick(askTimeOf(PERIOD).plus(RETRY_WAIT));
        assertThat(calls(PeriodCall.LEDGER_CHECK)).isEqualTo(2);
        assertThat(stateOf(PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("an ordinary closing reads the period's transfers once in the gateway and once in the ledger")
    void an_ordinary_closing_reads_the_periods_transfers_once_in_the_gateway_and_once_in_the_ledger() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);

        ticks(closingTimeOf(PERIOD), askTimeOf(PERIOD).plus(RETRY_WAIT), STEP);

        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_RECONCILIATION)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_CHECK)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_BOOKS)).isEqualTo(1);
        assertThat(ledger.callsTo("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD))).hasSize(1);
        assertThat(gateway.callsTo("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD))).hasSize(1);
    }

    @Test
    @DisplayName("the verdict rests on the counts kept when they agreed, not on the bank's counts at the verdict")
    void the_verdict_rests_on_the_counts_kept_when_they_agreed_not_on_the_banks_counts_at_the_verdict() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD, ACCEPTED + 5, POSTED + 5, REJECTED, REASONS));
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, POSTED + 5, REJECTED, REASONS, null));
        conductor.tick(askTimeOf(PERIOD));

        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
        var report = reportOf(PERIOD);
        assertThat(((Map<?, ?>) report.get("gateway")).get("accepted")).isEqualTo((int) ACCEPTED);
        assertThat(((Map<?, ?>) report.get("ledger")).get("postedTransfers")).isEqualTo((int) POSTED);
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
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, 0, 0, Map.of(), null));
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, PERIOD), 200, stats(PERIOD, 0, 0, 0));

        closeAndReconcile(PERIOD);

        var view = periodView(PERIOD);
        assertThat(view.get("verdict")).isEqualTo("BALANCED");
        assertThat(((Map<?, ?>) view.get("numbers")).get("accepted")).isEqualTo(0);
    }

    @Test
    @DisplayName("a bank that does not answer by the deadline fails the period with the reason, and the next period is closed and reconciled")
    void a_bank_that_does_not_answer_by_the_deadline_fails_the_period_with_the_reason_and_the_next_period_is_closed_and_reconciled() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 503);

        failsAtTheDeadlineWith("BANK_UNAVAILABLE");
        var failed = periodView(PERIOD);
        assertThat(failed.get("state")).isEqualTo("FAILED");
        assertThat(failed.get("verdict")).isNull();
        assertThat(reportOf(PERIOD)).isNull();

        bankAnswersFor(NEXT_PERIOD);
        closeAndReconcile(NEXT_PERIOD);
        assertThat(stateOf(NEXT_PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("a gateway that does not close the period is asked again after the wait and fails it at the deadline")
    void a_gateway_that_does_not_close_the_period_is_asked_again_after_the_wait_and_fails_it_at_the_deadline() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("POST", forPeriod(GatewayApi.CLOSE_PATH, PERIOD), 503);

        ticks(closingTimeOf(PERIOD), closingTimeOf(PERIOD).plus(RETRY_WAIT), STEP);
        assertThat(closeCalls(PERIOD)).isEqualTo(1);
        conductor.tick(closingTimeOf(PERIOD).plus(RETRY_WAIT));
        assertThat(closeCalls(PERIOD)).isEqualTo(2);

        conductor.tick(deadlineOf(PERIOD).minusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("CLOSING");
        conductor.tick(deadlineOf(PERIOD));
        assertThat(periodView(PERIOD).get("failure")).isEqualTo("PERIOD_NOT_CLOSED");
    }

    @Test
    @DisplayName("periods that ended while control was down are opened and reconciled one by one, oldest first")
    void periods_that_ended_while_control_was_down_are_opened_and_reconciled_one_by_one_oldest_first() {
        bankStartedIn(PERIOD);
        List.of(PERIOD, NEXT_PERIOD, THIRD_PERIOD).forEach(this::bankAnswersFor);
        var restarted = closingTimeOf(THIRD_PERIOD);

        conductor.tick(restarted);
        assertThat(periodsInOrder()).containsExactly("DRAINING", "OPEN", "OPEN", "OPEN");

        ticks(restarted.plusSeconds(1), restarted.plus(Duration.ofMinutes(20)), Duration.ofSeconds(30));
        assertThat(periodsInOrder()).containsExactly("RECONCILED", "RECONCILED", "RECONCILED", "OPEN");
        assertThat(gateway.calls.stream().filter(it -> it.method().equals("POST")).map(StubService.Call::path).toList()).containsExactly(
            forPeriod(GatewayApi.CLOSE_PATH, PERIOD), forPeriod(GatewayApi.CLOSE_PATH, NEXT_PERIOD), forPeriod(GatewayApi.CLOSE_PATH, THIRD_PERIOD));
    }

    @Test
    @DisplayName("a restarted control takes the next action when it is due, not before and not twice")
    void a_restarted_control_takes_the_next_action_when_it_is_due_not_before_and_not_twice() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        conductor.tick(closingTimeOf(PERIOD));

        controlRestarts();
        ticks(endOf(PERIOD).plus(Duration.ofMinutes(5)), countTimeOf(PERIOD), STEP);
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isZero();
        conductor.tick(countTimeOf(PERIOD));
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isEqualTo(1);

        controlRestarts();
        ticks(countTimeOf(PERIOD).plusSeconds(30), askTimeOf(PERIOD), STEP);
        assertThat(calls(PeriodCall.LEDGER_CHECK)).isZero();
        assertThat(checkRequests(PERIOD)).isEqualTo(1);
        conductor.tick(askTimeOf(PERIOD));
        assertThat(calls(PeriodCall.LEDGER_CHECK)).isEqualTo(1);
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isEqualTo(1);
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("two instances of control on one database take every action once")
    void two_instances_of_control_on_one_database_take_every_action_once() throws Exception {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        var other = new PeriodConductor(
            periodRepository, ledgerClient, gatewayClient, generatorClient, reportingClient, bankLive, chaosRepository, availabilityRepository,
            databaseClock, props, meters);

        for (var at = closingTimeOf(PERIOD); at.isBefore(askTimeOf(PERIOD).plus(RETRY_WAIT)); at = at.plus(STEP)) {
            var now = at;
            try (var both = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = both.submit(() -> conductor.tick(now));
                var second = both.submit(() -> other.tick(now));
                first.get();
                second.get();
            }
        }

        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
        assertThat(closeCalls(PERIOD)).isEqualTo(1);
        assertThat(calls(PeriodCall.GATEWAY_SUMMARY)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_RECONCILIATION)).isEqualTo(1);
        assertThat(checkRequests(PERIOD)).isEqualTo(1);
        assertThat(clientRequests(PERIOD)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_CHECK)).isEqualTo(1);
        assertThat(calls(PeriodCall.LEDGER_BOOKS)).isEqualTo(1);
    }

    @Test
    @DisplayName("counts that agree only at the deadline are asked about once, by the instance that counted them")
    void counts_that_agree_only_at_the_deadline_are_asked_about_once_by_the_instance_that_counted_them() throws Exception {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 503);
        tickUpToTheDeadline(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD));
        ledger.answer("GET", forPeriod(LedgerApi.CHECK_PATH, PERIOD), 200, mapOf("check", check(true)), Duration.ofSeconds(1));
        var other = new PeriodConductor(
            periodRepository, ledgerClient, gatewayClient, generatorClient, reportingClient, bankLive, chaosRepository, availabilityRepository,
            databaseClock, props, meters);

        try (var both = Executors.newVirtualThreadPerTaskExecutor()) {
            var counting = both.submit(() -> conductor.tick(deadlineOf(PERIOD)));
            while (checkRequests(PERIOD) == 0) {
                Thread.onSpinWait();
            }
            other.tick(deadlineOf(PERIOD));
            counting.get();
        }

        assertThat(calls(PeriodCall.LEDGER_CHECK)).isEqualTo(1);
        assertThat(stateOf(PERIOD)).isIn("RECONCILED", "FAILED");
    }

    @Test
    @DisplayName("a period control left half settled when it went down is settled after the restart, with its deadline started again")
    void a_period_control_left_half_settled_when_it_went_down_is_settled_after_the_restart_with_its_deadline_started_again() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        checkNotMade(PERIOD);
        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));
        assertThat(checkRequests(PERIOD)).isEqualTo(1);

        controlRestarts();
        var back = deadlineOf(PERIOD).plusSeconds(60);
        conductor.tick(back);
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");
        assertThat(checkRequests(PERIOD)).isEqualTo(2);

        ledger.answer("GET", forPeriod(LedgerApi.CHECK_PATH, PERIOD), 200, mapOf("check", check(true)));
        conductor.tick(back.plus(RETRY_WAIT));
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("after a restart a count is not compared with one made before it: the time between says nothing about the bank")
    void after_a_restart_a_count_is_not_compared_with_one_made_before_it_the_time_between_says_nothing_about_the_bank() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        notDrained(PERIOD, POSTED - 10);
        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));

        controlRestarts();
        conductor.tick(countTimeOf(PERIOD).plusSeconds(60));
        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT.multipliedBy(2)));
        assertThat(periodView(PERIOD).get("failure")).isEqualTo("DRAIN_STALLED");
    }

    private void window(Instant from, Instant to) {
        jdbc.sql("INSERT INTO maintenance_windows (starts_at, ends_at) VALUES (:from, :to)")
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .update();
    }

    @Test
    @DisplayName("a bank that is down during declared maintenance does not fail the period: it is reconciled once the bank is back")
    void a_bank_that_is_down_during_declared_maintenance_does_not_fail_the_period_it_is_reconciled_once_the_bank_is_back() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 503);
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 503);
        var closing = closingTimeOf(PERIOD);
        var windowEnd = closing.plus(Duration.ofMinutes(30));
        window(closing.minusSeconds(120), windowEnd);

        conductor.tick(closing);
        ticks(countTimeOf(PERIOD), windowEnd, RETRY_WAIT);
        conductor.tick(windowEnd.minusSeconds(1));
        conductor.tick(windowEnd.plus(Duration.ofMinutes(30)));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        bankAnswersFor(PERIOD);
        conductor.tick(windowEnd.plus(Duration.ofMinutes(35)));
        conductor.tick(windowEnd.minusSeconds(1).plus(VERDICT_DEADLINE).minus(CLOSE_DELAY));
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("after the maintenance window the deadline runs again, and a cancelled window does not hold it")
    void after_the_maintenance_window_the_deadline_runs_again_and_a_cancelled_window_does_not_hold_it() {
        bankStartedIn(PERIOD);
        List.of(PERIOD, NEXT_PERIOD).forEach(this::bankAnswersFor);
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 503);
        var closing = closingTimeOf(PERIOD);
        var windowEnd = closing.plus(Duration.ofMinutes(30));
        window(closing.minusSeconds(120), windowEnd);
        conductor.tick(closing);
        conductor.tick(windowEnd.minusSeconds(1));

        conductor.tick(windowEnd.minusSeconds(1).plus(VERDICT_DEADLINE).minus(CLOSE_DELAY));
        assertThat(periodView(PERIOD).get("failure")).isEqualTo("BANK_UNAVAILABLE");

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, NEXT_PERIOD), 503);
        var nextClosing = closingTimeOf(NEXT_PERIOD);
        window(nextClosing.minusSeconds(120), nextClosing.plus(Duration.ofMinutes(30)));
        jdbc.sql("UPDATE maintenance_windows SET cancelled_at = declared_at WHERE starts_at = :from")
            .param("from", Timestamp.from(nextClosing.minusSeconds(120)))
            .update();
        conductor.tick(nextClosing);
        conductor.tick(deadlineOf(NEXT_PERIOD));
        assertThat(periodView(NEXT_PERIOD).get("failure")).isEqualTo("BANK_UNAVAILABLE");
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
    @DisplayName("a period the ledger does not know when asked about its check fails at once")
    void a_period_the_ledger_does_not_know_when_asked_about_its_check_fails_at_once() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        ledger.answer("GET", forPeriod(LedgerApi.CHECK_PATH, PERIOD), 404, mapOf("error", "unknown-period"));

        closeAndReconcile(PERIOD);

        assertThat(periodView(PERIOD).get("failure")).isEqualTo("PERIOD_UNKNOWN_TO_BANK");
    }

    @Test
    @DisplayName("a check not there when asked about is asked for again in the same question")
    void a_check_not_there_when_asked_about_is_asked_for_again_in_the_same_question() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        checkNotMade(PERIOD);
        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));
        assertThat(checkRequests(PERIOD)).isEqualTo(1);

        conductor.tick(askTimeOf(PERIOD));
        assertThat(checkRequests(PERIOD)).isEqualTo(2);
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        ledger.answer("GET", forPeriod(LedgerApi.CHECK_PATH, PERIOD), 200, mapOf("check", check(true)));
        conductor.tick(askTimeOf(PERIOD).plus(RETRY_WAIT));
        assertThat(stateOf(PERIOD)).isEqualTo("RECONCILED");
        assertThat(checkRequests(PERIOD)).isEqualTo(2);
    }

    @Test
    @DisplayName("the clients' reconciliation is started again when asked about and the generator forgot it or failed at it, not while it runs")
    void the_clients_reconciliation_is_started_again_when_asked_about_and_the_generator_forgot_it_or_failed_at_it_not_while_it_runs() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        var path = forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD);
        generator.answer("GET", path, 200, clientReconciliation(PERIOD, "NOT_STARTED"));
        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));
        assertThat(clientRequests(PERIOD)).isEqualTo(1);

        conductor.tick(askTimeOf(PERIOD));
        assertThat(clientRequests(PERIOD)).isEqualTo(2);

        generator.answer("GET", path, 200, clientReconciliation(PERIOD, "RUNNING"));
        conductor.tick(askTimeOf(PERIOD).plus(RETRY_WAIT));
        assertThat(clientRequests(PERIOD)).isEqualTo(2);

        generator.answer("GET", path, 200, mapOf("periodStart", PERIOD, "state", "FAILED", "failure", "GATEWAY_UNAVAILABLE", "report", null));
        conductor.tick(askTimeOf(PERIOD).plus(RETRY_WAIT.multipliedBy(2)));
        assertThat(clientRequests(PERIOD)).isEqualTo(3);

        generator.answer("GET", path, 200, clientReconciliation(PERIOD, "DONE"));
        conductor.tick(askTimeOf(PERIOD).plus(RETRY_WAIT.multipliedBy(3)));
        assertThat(stateOf(PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("two counts in a row without progress fail the period with its counts, and the ledger is not asked to check books still open")
    void two_counts_in_a_row_without_progress_fail_the_period_with_its_counts_and_the_ledger_is_not_asked_to_check_books_still_open() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        notDrained(PERIOD, POSTED - 10);
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, PERIOD), 200, reconciliation(PERIOD, POSTED - 1, REJECTED, REASONS, null));
        conductor.tick(closingTimeOf(PERIOD));

        ticks(countTimeOf(PERIOD), countTimeOf(PERIOD).plus(RETRY_WAIT), STEP);
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT));
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

        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));

        assertThat(periodView(PERIOD).get("failure")).isEqualTo("MORE_RESULTS_THAN_ACCEPTED");
        assertThat(checkRequests(PERIOD)).isZero();
    }

    @Test
    @DisplayName("a clients' reconciliation that says it is done and carries no report fails the period at the deadline, and the next period goes on")
    void a_clients_reconciliation_that_says_it_is_done_and_carries_no_report_fails_the_period_at_the_deadline_and_the_next_period_goes_on() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        generator.answer(
            "GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, PERIOD), 200, mapOf("periodStart", PERIOD, "state", "DONE", "failure", null, "report", null));

        failsAtTheDeadlineWith("CLIENT_RECONCILIATION_UNAVAILABLE");

        bankAnswersFor(NEXT_PERIOD);
        closeAndReconcile(NEXT_PERIOD);
        assertThat(stateOf(NEXT_PERIOD)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("a drain that moves between counts is waited for")
    void a_drain_that_moves_between_counts_is_waited_for() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        notDrained(PERIOD, POSTED - 10);
        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));

        notDrained(PERIOD, POSTED - 5);
        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD));
        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT.multipliedBy(2)));
        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT.multipliedBy(2)).plus(CHECK_WAIT));
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("a drain that still moves at the deadline fails as a drain timed out, forty minutes after the period's end, and keeps its counts")
    void a_drain_that_still_moves_at_the_deadline_fails_as_a_drain_timed_out_forty_minutes_after_the_periods_end_and_keeps_its_counts() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        conductor.tick(closingTimeOf(PERIOD));
        var posted = POSTED - 100;
        for (var at = countTimeOf(PERIOD); at.isBefore(deadlineOf(PERIOD)); at = at.plus(RETRY_WAIT)) {
            notDrained(PERIOD, posted++);
            conductor.tick(at);
        }
        notDrained(PERIOD, posted++);
        conductor.tick(deadlineOf(PERIOD).minusSeconds(1));
        assertThat(stateOf(PERIOD)).isEqualTo("DRAINING");

        notDrained(PERIOD, posted);
        conductor.tick(deadlineOf(PERIOD));

        var view = periodView(PERIOD);
        assertThat(view.get("failure")).isEqualTo("DRAIN_TIMED_OUT");
        assertThat(((Map<?, ?>) view.get("numbers")).get("accepted")).isEqualTo((int) ACCEPTED);
    }

    @Test
    @DisplayName("a drained period whose check never comes fails at the deadline as check unavailable")
    void a_drained_period_whose_check_never_comes_fails_at_the_deadline_as_check_unavailable() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        checkNotMade(PERIOD);

        failsAtTheDeadlineWith("LEDGER_CHECK_UNAVAILABLE");
    }

    @Test
    @DisplayName("books the ledger does not sum by the deadline fail the period as books unavailable")
    void books_the_ledger_does_not_sum_by_the_deadline_fail_the_period_as_books_unavailable() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        ledger.answer("GET", LedgerApi.BOOKS_PATH, 503);

        failsAtTheDeadlineWith("BOOKS_UNAVAILABLE");
    }

    @Test
    @DisplayName("results that arrive between the gateway's two reads are not taken for a difference in the reasons: the period is counted again")
    void results_that_arrive_between_the_gateways_two_reads_are_not_taken_for_a_difference_in_the_reasons_the_period_is_counted_again() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        gateway.answer(
            "GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200,
            summary(PERIOD, ACCEPTED, POSTED, REJECTED, Map.of("INSUFFICIENT_FUNDS", 60L, "BLOCKED_ACCOUNT", 39L)));
        conductor.tick(closingTimeOf(PERIOD));
        conductor.tick(countTimeOf(PERIOD));
        assertThat(checkRequests(PERIOD)).isZero();

        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, PERIOD), 200, summary(PERIOD));
        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT));
        conductor.tick(countTimeOf(PERIOD).plus(RETRY_WAIT).plus(CHECK_WAIT));
        assertThat(periodView(PERIOD).get("verdict")).isEqualTo("BALANCED");
    }

    @Test
    @DisplayName("without the reporting service the period is reconciled with a note and no turnover")
    void without_the_reporting_service_the_period_is_reconciled_with_a_note_and_no_turnover() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, PERIOD), 503);

        closeAndReconcile(PERIOD);

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
