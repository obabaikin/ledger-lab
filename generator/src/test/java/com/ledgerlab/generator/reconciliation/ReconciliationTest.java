package com.ledgerlab.generator.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.FakeGateway;
import com.ledgerlab.generator.GeneratorErrors;
import com.ledgerlab.generator.IntegrationTest;
import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.Statement;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.journal.JournalOutcome;
import com.ledgerlab.generator.journal.Period;
import com.ledgerlab.generator.journal.UnknownResolved;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ReconciliationTest extends IntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private Period period;
    /** A time in the period; each call of {@link #next()} moves it on, so transfers have an order. */
    private Instant at;

    @BeforeEach
    void theLastEndedPeriod() {
        period = lastEndedPeriod();
        at = period.start().plusSeconds(10);
    }

    private Instant next() {
        at = at.plusMillis(1);
        return at;
    }

    private static String path(Instant periodStart) {
        return "/internal/periods/" + periodStart + "/reconciliation";
    }

    private PeriodReconciliation get(Period period) {
        return http.getForObject(path(period.start()), PeriodReconciliation.class);
    }

    private PeriodReconciliation reconcile(Period period) {
        var started = http.postForEntity(path(period.start()), null, PeriodReconciliation.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return await().atMost(Duration.ofSeconds(30))
            .until(() -> get(period), it -> it.state() == ReconciliationState.DONE || it.state() == ReconciliationState.FAILED);
    }

    private ClientReconciliationReport report() {
        var reconciliation = reconcile(period);
        assertThat(reconciliation.failure()).isNull();
        assertThat(reconciliation.state()).isEqualTo(ReconciliationState.DONE);
        return reconciliation.report();
    }

    private void assertFailed(ReconciliationFailure failure) {
        var reconciliation = reconcile(period);
        assertThat(reconciliation.state()).isEqualTo(ReconciliationState.FAILED);
        assertThat(reconciliation.failure()).isEqualTo(failure);
        assertThat(reconciliation.report()).isNull();
    }

    private void bankBehaves(FakeGateway.Options behaviour) {
        var bank = Map.copyOf(gateway.accepted);
        gateway.reset(behaviour.periodLength(props.periods().length()));
        gateway.accepted.putAll(bank);
    }

    @Test
    @DisplayName("a period in which the client and the bank agree is balanced, read page by page")
    void a_period_in_which_the_client_and_the_bank_agree_is_balanced_read_page_by_page() {
        for (var i = 0; i < 5; i++) {
            agreed(next());
        }
        var rejected = intent(next());
        outcome(at, rejected, JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS, at);
        gateway.accept(rejected, 1, 2, 100, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS, at);
        var acceptedOnly = intent(next());
        outcome(at, acceptedOnly, JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED, null, at);
        gateway.accept(acceptedOnly, 1, 2, 100, TransferStatus.POSTED, null, at);
        var notTaken = intent(next());
        outcome(at, notTaken, JournalOutcome.FAILED, null, null, null);
        bankBehaves(FakeGateway.with().statementPage(3));

        var report = report();

        assertThat(report.periodStart()).isEqualTo(period.start());
        assertThat(report.balanced()).isTrue();
        assertThat(report.statementFinal()).isTrue();
        assertThat(report.acceptedByJournal()).isEqualTo(7);
        assertThat(report.statement().rows()).isEqualTo(7);
        assertThat(report.statement().amountCents()).isEqualTo(700);
        assertThat(report.statement().byStatus()).containsOnly(entry(TransferStatus.POSTED, 6L), entry(TransferStatus.REJECTED, 1L));
        assertThat(report.statement().unknownToClient()).isZero();
        assertThat(report.unknownResolved()).isEqualTo(new UnknownResolved(0, 0));
        assertThat(gateway.statementRequests.get()).isEqualTo(3);
    }

    @Test
    void a_period_without_transfers_is_balanced_with_zeros() {
        var report = report();

        assertThat(report.balanced()).isTrue();
        assertThat(report.statementFinal()).isTrue();
        assertThat(report.statement().rows()).isZero();
        assertThat(report.acceptedByJournal()).isZero();
    }

    @Test
    void a_transfer_the_bank_accepted_that_is_not_in_the_client_journal_is_a_break() {
        agreed(next());
        var unknownToTheClient = UUID.randomUUID();
        gateway.accept(unknownToTheClient, 3, 4, 250, TransferStatus.POSTED, null, next());

        var report = report();

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().notInJournal()).isEqualTo(new TransferIds(1, java.util.List.of(unknownToTheClient)));
        assertThat(report.statement().rows()).isEqualTo(2);
        assertThat(report.acceptedByJournal()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transfer sent at the end of one period and accepted at the start of the next is found in the journal")
    void a_transfer_sent_at_the_end_of_one_period_and_accepted_at_the_start_of_the_next_is_found_in_the_journal() {
        var sentAt = period.start().minusMillis(20);
        var acceptedAt = period.start().plusMillis(5);
        var transferId = intent(sentAt);
        outcome(sentAt, transferId, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, acceptedAt);
        gateway.accept(transferId, 1, 2, 100, TransferStatus.POSTED, null, acceptedAt);

        var report = report();

        assertThat(report.balanced()).isTrue();
        assertThat(report.statement().rows()).isEqualTo(1);
        assertThat(report.acceptedByJournal()).isEqualTo(1);
    }

    @Test
    void a_transfer_accepted_in_the_period_before_is_not_expected_in_this_periods_statement() {
        agreed(period.start().minusSeconds(5));
        agreed(next());

        var report = report();

        assertThat(report.balanced()).isTrue();
        assertThat(report.statement().rows()).isEqualTo(1);
        assertThat(report.acceptedByJournal()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transfer the bank told the client it accepted in the period, missing from the statement, is a break")
    void a_transfer_the_bank_told_the_client_it_accepted_in_the_period_missing_from_the_statement_is_a_break() {
        agreed(next());
        var lost = intent(next());
        outcome(at, lost, JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED, null, at);

        var report = report();

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().acknowledgedNotInStatement()).isEqualTo(new TransferIds(1, java.util.List.of(lost)));
        assertThat(report.acceptedByJournal()).isEqualTo(2);
        assertThat(report.statement().rows()).isEqualTo(1);
    }

    @Test
    void a_transfer_the_client_was_told_the_bank_did_not_take_but_the_statement_lists_is_a_break() {
        var refused = intent(next());
        outcome(at, refused, JournalOutcome.FAILED, null, null, null);
        gateway.accept(refused, 1, 2, 100, TransferStatus.POSTED, null, at);

        var report = report();

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().failedButInStatement()).isEqualTo(new TransferIds(1, java.util.List.of(refused)));
    }

    @Test
    void a_transfer_with_another_amount_at_the_bank_is_a_break_with_both_sides_shown() {
        var transferId = intent(next(), 1, 2, 100);
        outcome(at, transferId, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, at);
        gateway.accept(transferId, 1, 2, 1_000, TransferStatus.POSTED, null, at);

        var report = report();

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().payloadMismatch().count()).isEqualTo(1);
        assertThat(report.breaks().payloadMismatch().examples()).containsExactly(new TransferSides.MismatchedTransfer(
            transferId,
            new TransferSides.TransferSide(1, 2, 100, TransferStatus.POSTED, null),
            new TransferSides.TransferSide(1, 2, 1_000, TransferStatus.POSTED, null)));
        assertThat(report.breaks().statusMismatch().count()).isZero();
    }

    @Test
    @DisplayName("a final status or a reason the client was told that differs from the statement's is a break")
    void a_final_status_or_a_reason_the_client_was_told_that_differs_from_the_statements_is_a_break() {
        var otherStatus = intent(next());
        outcome(at, otherStatus, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, at);
        gateway.accept(otherStatus, 1, 2, 100, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT, at);
        var otherReason = intent(next());
        outcome(at, otherReason, JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS, at);
        gateway.accept(otherReason, 1, 2, 100, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT, at);
        // A reason this client could not read is not held against the bank.
        var unreadReason = intent(next());
        outcome(at, unreadReason, JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, null, at);
        gateway.accept(unreadReason, 1, 2, 100, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT, at);

        var report = report();

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().statusMismatch().count()).isEqualTo(2);
        assertThat(report.breaks().statusMismatch().examples()).extracting(TransferSides.MismatchedTransfer::transferId)
            .containsExactlyInAnyOrder(otherStatus, otherReason);
        assertThat(report.breaks().statusMismatch().examples()).filteredOn(it -> it.transferId().equals(otherStatus)).singleElement()
            .satisfies(example -> {
                assertThat(example.client().status()).isEqualTo(TransferStatus.POSTED);
                assertThat(example.bank().status()).isEqualTo(TransferStatus.REJECTED);
                assertThat(example.bank().rejectReason()).isEqualTo(RejectReason.BLOCKED_ACCOUNT);
            });
    }

    @Test
    @DisplayName("an intent a stopped generator left without an outcome is asked about at the bank, and the answer is written to the journal")
    void an_intent_a_stopped_generator_left_without_an_outcome_is_asked_about_at_the_bank_and_the_answer_is_written_to_the_journal() {
        var sentAt = period.start().minusSeconds(2);
        var acceptedInThisPeriod = intent(sentAt);
        gateway.accept(acceptedInThisPeriod, 1, 2, 100, TransferStatus.POSTED, null, period.start().plusMillis(1));
        var acceptedInItsOwnPeriod = intent(sentAt.minusSeconds(1));
        gateway.accept(acceptedInItsOwnPeriod, 1, 2, 100, TransferStatus.POSTED, null, sentAt);
        var neverArrived = intent(sentAt.minusSeconds(2));

        var report = report();

        assertThat(report.unknownResolved()).isEqualTo(new UnknownResolved(2, 1));
        assertThat(storedOutcome(acceptedInThisPeriod)).isEqualTo(JournalOutcome.UNKNOWN_ACCEPTED.name());
        assertThat(storedOutcome(acceptedInItsOwnPeriod)).isEqualTo(JournalOutcome.UNKNOWN_ACCEPTED.name());
        assertThat(storedOutcome(neverArrived)).isEqualTo(JournalOutcome.UNKNOWN_NOT_ACCEPTED.name());
        assertThat(report.balanced()).isTrue();
        assertThat(report.statement().rows()).isEqualTo(1);
        assertThat(report.statement().unknownToClient()).isEqualTo(1);
        assertThat(report.acceptedByJournal()).isEqualTo(1);
        assertThat(gateway.transferQuestions.get()).isEqualTo(3);
    }

    @Test
    void a_transfer_the_bank_said_it_accepted_when_asked_but_does_not_list_in_its_statement_is_a_break() {
        var sentAt = period.start().minusSeconds(2);
        var transferId = intent(sentAt);
        gateway.accept(transferId, 1, 2, 100, TransferStatus.POSTED, null, next());
        // The statement's first page is served without it.
        bankBehaves(FakeGateway.with().tamperStatement((request, page) -> new Statement(0, java.util.List.of(), null)));

        var report = report();

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().acknowledgedNotInStatement()).isEqualTo(new TransferIds(1, java.util.List.of(transferId)));
    }

    @Test
    void an_intent_of_this_period_without_an_outcome_that_the_statement_lists_is_not_a_break() {
        var transferId = intent(next());
        gateway.accept(transferId, 1, 2, 100, TransferStatus.POSTED, null, at);

        var report = report();

        assertThat(report.balanced()).isTrue();
        assertThat(report.statement().unknownToClient()).isEqualTo(1);
        assertThat(outcomeRows(transferId)).as("unknown transfers of a period are asked about when the next one is reconciled").isZero();
    }

    @Test
    @DisplayName("a bank that does not answer about an unknown transfer fails the reconciliation; nothing is written as not accepted")
    void a_bank_that_does_not_answer_about_an_unknown_transfer_fails_the_reconciliation_nothing_is_written_as_not_accepted() {
        var transferId = intent(period.start().minusSeconds(2));
        bankBehaves(FakeGateway.with().transferFailure(HttpStatus.SERVICE_UNAVAILABLE.value()));

        assertFailed(ReconciliationFailure.GATEWAY_UNAVAILABLE);

        assertThat(outcomeRows(transferId)).isZero();
    }

    @Test
    @DisplayName("a period the bank has not closed fails the reconciliation, and asking again starts it anew")
    void a_period_the_bank_has_not_closed_fails_the_reconciliation_and_asking_again_starts_it_anew() {
        agreed(next());
        bankBehaves(FakeGateway.with().periodNotClosed());

        assertFailed(ReconciliationFailure.PERIOD_NOT_CLOSED);

        bankBehaves(FakeGateway.with());
        assertThat(report().balanced()).isTrue();
    }

    @Test
    void a_report_that_is_done_is_served_again_without_reading_the_statement_again() {
        agreed(next());
        var first = report();
        var statementRequests = gateway.statementRequests.get();

        var again = get(period);

        assertThat(again.state()).isEqualTo(ReconciliationState.DONE);
        assertThat(again.report()).isEqualTo(first);
        assertThat(gateway.statementRequests.get()).isEqualTo(statementRequests);
    }

    @Test
    void asked_for_again_a_reconciliation_runs_again_on_what_the_journal_and_the_bank_hold_now() {
        agreed(next());
        assertThat(report().statement().rows()).isEqualTo(1);

        agreed(next());

        assertThat(report().statement().rows()).isEqualTo(2);
    }

    @Test
    void a_gateway_that_fails_in_the_middle_of_the_statement_fails_the_reconciliation() {
        for (var i = 0; i < 4; i++) {
            agreed(next());
        }
        bankBehaves(FakeGateway.with().statementPage(2).statementFailure(request -> request == 2 ? HttpStatus.INTERNAL_SERVER_ERROR.value() : null));

        assertFailed(ReconciliationFailure.GATEWAY_UNAVAILABLE);
    }

    @Test
    void a_statement_that_lists_a_transfer_twice_is_a_bad_answer_not_a_report() {
        for (var i = 0; i < 4; i++) {
            agreed(next());
        }
        var firstPage = new ArrayList<Statement>();
        bankBehaves(FakeGateway.with().statementPage(2).tamperStatement((request, page) -> {
            firstPage.add(page);
            return new Statement(page.entryCount(), firstPage.getFirst().items(), page.next());
        }));

        assertFailed(ReconciliationFailure.BAD_GATEWAY_ANSWER);
    }

    @Test
    void a_statement_whose_entry_count_changes_between_pages_is_a_bad_answer() {
        for (var i = 0; i < 4; i++) {
            agreed(next());
        }
        bankBehaves(FakeGateway.with().statementPage(2)
            .tamperStatement((request, page) -> new Statement(page.entryCount() + request, page.items(), page.next())));

        assertFailed(ReconciliationFailure.BAD_GATEWAY_ANSWER);
    }

    @Test
    void a_statement_with_fewer_lines_than_its_entry_count_is_a_bad_answer() {
        for (var i = 0; i < 4; i++) {
            agreed(next());
        }
        bankBehaves(FakeGateway.with().statementPage(2).tamperStatement((request, page) -> new Statement(page.entryCount(), page.items(), null)));

        assertFailed(ReconciliationFailure.BAD_GATEWAY_ANSWER);
    }

    @Test
    void a_report_lists_no_more_examples_of_a_break_than_the_limit_and_counts_them_all() {
        var limit = props.reconciliation().exampleLimit();
        for (var i = 0; i < limit + 5; i++) {
            gateway.accept(UUID.randomUUID(), 1, 2, 100, TransferStatus.POSTED, null, next());
        }

        var report = report();

        assertThat(report.breaks().notInJournal().count()).isEqualTo(limit + 5);
        assertThat(report.breaks().notInJournal().transferIds()).hasSize(limit).doesNotHaveDuplicates();
    }

    @Test
    void a_statement_line_that_is_not_final_makes_the_statement_not_final_but_is_not_a_break() {
        var transferId = intent(next());
        outcome(at, transferId, JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED, null, at);
        gateway.accept(transferId, 1, 2, 100, TransferStatus.PUBLISHED, null, at);

        var report = report();

        assertThat(report.statementFinal()).isFalse();
        assertThat(report.balanced()).isTrue();
    }

    @Test
    void before_it_is_asked_for_a_reconciliation_is_not_started() {
        var reconciliation = get(period.previous());

        assertThat(reconciliation).isEqualTo(PeriodReconciliation.of(period.previous().start(), ReconciliationState.NOT_STARTED));
    }

    @Test
    @DisplayName("a period whose previous period the journal no longer holds is unknown: a transfer sent before it could not be found")
    void a_period_whose_previous_period_the_journal_no_longer_holds_is_unknown_a_transfer_sent_before_it_could_not_be_found() {
        var lastHeld = Period.containing(Instant.now().minus(props.periods().retention()), props.periods().length());

        var answer = http.exchange(path(lastHeld.start()), HttpMethod.GET, null, JSON_OBJECT);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(answer.getBody()).containsEntry("error", GeneratorErrors.UNKNOWN_PERIOD);
        assertThat(get(lastHeld.next()).state()).isEqualTo(ReconciliationState.NOT_STARTED);
    }

    @Test
    @DisplayName("a time off the grid, a period that has not ended and one older than the journal holds are unknown periods")
    void a_time_off_the_grid_a_period_that_has_not_ended_and_one_older_than_the_journal_holds_are_unknown_periods() {
        var running = period.next();
        var tooOld = Period.containing(Instant.now().minus(props.periods().retention()), props.periods().length()).previous();

        for (var start : java.util.List.of(period.start().plusSeconds(1), running.start(), tooOld.start())) {
            for (var method : java.util.List.of(HttpMethod.POST, HttpMethod.GET)) {
                ResponseEntity<Map<String, Object>> answer = http.exchange(path(start), method, null, JSON_OBJECT);
                assertThat(answer.getStatusCode()).as(method + " " + start).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(answer.getBody()).containsEntry("error", GeneratorErrors.UNKNOWN_PERIOD);
            }
        }
        assertThat(gateway.statementRequests.get()).isZero();
    }
}
