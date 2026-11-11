package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.PeriodSummary;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.generator.ReconciliationBreaks;
import com.ledgerlab.control.generator.StatementTotals;
import com.ledgerlab.control.generator.TransferIds;
import com.ledgerlab.control.generator.TransferSides;
import com.ledgerlab.control.generator.UnknownResolved;
import com.ledgerlab.control.ledger.AccountMismatch;
import com.ledgerlab.control.ledger.Books;
import com.ledgerlab.control.ledger.LedgerCheck;
import com.ledgerlab.control.ledger.LedgerReconciliation;
import com.ledgerlab.control.ledger.PeriodCheck;
import com.ledgerlab.control.periods.Mismatch;
import com.ledgerlab.control.periods.PeriodNote;
import com.ledgerlab.control.periods.PeriodReport;
import com.ledgerlab.control.periods.Verdict;
import com.ledgerlab.control.periods.Verdicts;
import com.ledgerlab.control.reporting.PeriodStats;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VerdictTest {

    private static final Instant PERIOD = Instant.parse("2026-11-10T11:00:00Z");
    private static final Map<String, Long> REASONS = Map.of("INSUFFICIENT_FUNDS", 100L);
    private static final TransferIds NO_IDS = new TransferIds(0, List.of());
    private static final TransferSides NO_SIDES = new TransferSides(0, List.of());

    private static PeriodSummary gateway(long accepted, long posted, long rejected) {
        return new PeriodSummary(PERIOD, accepted, accepted, posted, rejected, rejected == 0 ? Map.of() : Map.of("INSUFFICIENT_FUNDS", rejected));
    }

    private static LedgerReconciliation ledger(long posted, long rejected) {
        return new LedgerReconciliation(PERIOD, posted, rejected, rejected == 0 ? Map.of() : Map.of("INSUFFICIENT_FUNDS", rejected), 7, null);
    }

    private static PeriodCheck check(boolean balanced) {
        return new PeriodCheck(
            1800, 5000, 5000, 0, List.of(), true, balanced ? 0 : 1, balanced ? List.of() : List.of(new AccountMismatch(17, -500, -400)),
            balanced ? List.of() : List.of(LedgerCheck.EVERY_ACCOUNT_MATCHES_JOURNAL), balanced);
    }

    private static Books books(boolean balanced) {
        return new Books(100, 1_000_000, balanced ? 1_000_000 : 999_900, balanced);
    }

    private static ClientReconciliationReport client(boolean balanced, boolean statementFinal, TransferIds acknowledgedNotInStatement) {
        return new ClientReconciliationReport(
            PERIOD, balanced, statementFinal, 1000, new StatementTotals(1000, 5000, Map.of("POSTED", 900L, "REJECTED", 100L), 0),
            new ReconciliationBreaks(acknowledgedNotInStatement, NO_IDS, NO_IDS, NO_SIDES, NO_SIDES), new UnknownResolved(0, 0));
    }

    private static ClientReconciliationReport client() {
        return client(true, true, NO_IDS);
    }

    private static PeriodStats stats(long accepted, long posted, long rejected) {
        return new PeriodStats(PERIOD, PERIOD.plusSeconds(3700), accepted, accepted, posted, rejected, 5000, REASONS);
    }

    private static PeriodReport verdict(PeriodSummary gateway, LedgerReconciliation ledger) {
        return Verdicts.verdictOf(gateway.accepted(), gateway, ledger, check(true), books(true), client(), stats(1000, 900, 100), List.of());
    }

    @Test
    @DisplayName("a period whose every view agrees is balanced, without mismatches or notes")
    void a_period_whose_every_view_agrees_is_balanced_without_mismatches_or_notes() {
        var report = verdict(gateway(1000, 900, 100), ledger(900, 100));

        assertThat(report.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.notes()).isEmpty();
    }

    @Test
    @DisplayName("a period without transfers is balanced with zeros")
    void a_period_without_transfers_is_balanced_with_zeros() {
        var report = Verdicts.verdictOf(0, gateway(0, 0, 0), ledger(0, 0), check(true), books(true), client(), stats(0, 0, 0), List.of());

        assertThat(report.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(report.gateway().accepted()).isZero();
    }

    @Test
    @DisplayName("a transfer the clients were told of and the statement lacks is a mismatch with its id, though every total agrees")
    void a_transfer_the_clients_were_told_of_and_the_statement_lacks_is_a_mismatch_with_its_id_though_every_total_agrees() {
        var lost = UUID.randomUUID();
        var gateway = gateway(1000, 900, 100);

        var report = Verdicts.verdictOf(
            1000, gateway, ledger(900, 100), check(true), books(true), client(false, true, new TransferIds(1, List.of(lost))),
            stats(1000, 900, 100), List.of());

        assertThat(report.verdict()).isEqualTo(Verdict.MISMATCH);
        assertThat(report.mismatches()).containsExactly(Mismatch.CLIENT_JOURNAL_DISAGREES);
        assertThat(report.client().breaks().acknowledgedNotInStatement().transferIds()).containsExactly(lost);
    }

    @Test
    @DisplayName("an account whose balance moved past its journal is a mismatch with the account")
    void an_account_whose_balance_moved_past_its_journal_is_a_mismatch_with_the_account() {
        var gateway = gateway(1000, 900, 100);

        var report = Verdicts.verdictOf(1000, gateway, ledger(900, 100), check(false), books(true), client(), stats(1000, 900, 100), List.of());

        assertThat(report.mismatches()).containsExactly(Mismatch.LEDGER_CHECK_FAILED);
        assertThat(report.ledgerCheck().accountExamples()).containsExactly(new AccountMismatch(17, -500, -400));
    }

    @Test
    void books_that_do_not_add_up_to_the_opening_sum_are_a_mismatch() {
        var gateway = gateway(1000, 900, 100);

        var report = Verdicts.verdictOf(1000, gateway, ledger(900, 100), check(true), books(false), client(), stats(1000, 900, 100), List.of());

        assertThat(report.mismatches()).containsExactly(Mismatch.BOOKS_OUT_OF_BALANCE);
    }

    @Test
    @DisplayName("gateway and ledger counting the outcomes differently is a mismatch, also when only the reasons differ")
    void gateway_and_ledger_counting_the_outcomes_differently_is_a_mismatch_also_when_only_the_reasons_differ() {
        var byOutcome = verdict(gateway(1000, 901, 99), ledger(900, 100));
        var byReason = verdict(
            new PeriodSummary(PERIOD, 1000, 1000, 900, 100, Map.of("BLOCKED_ACCOUNT", 100L)), ledger(900, 100));

        assertThat(byOutcome.mismatches()).containsExactly(Mismatch.BANK_VIEWS_DISAGREE);
        assertThat(byReason.mismatches()).containsExactly(Mismatch.BANK_VIEWS_DISAGREE);
    }

    @Test
    @DisplayName("a check that could not compare the accounts with the period before is noted and does not change the verdict")
    void a_check_that_could_not_compare_the_accounts_with_the_period_before_is_noted_and_does_not_change_the_verdict() {
        var gateway = gateway(1000, 900, 100);
        var notCompared = new PeriodCheck(1800, 5000, 5000, 0, List.of(), false, 0, List.of(), List.of(), true);

        var report = Verdicts.verdictOf(1000, gateway, ledger(900, 100), notCompared, books(true), client(), stats(1000, 900, 100), List.of());

        assertThat(report.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(report.notes()).containsExactly(PeriodNote.ACCOUNTS_NOT_COMPARED);
    }

    @Test
    @DisplayName("a period that holds another number of transfers than when it was closed is a mismatch")
    void a_period_that_holds_another_number_of_transfers_than_when_it_was_closed_is_a_mismatch() {
        var gateway = gateway(1000, 900, 100);

        var report = Verdicts.verdictOf(999, gateway, ledger(900, 100), check(true), books(true), client(), stats(1000, 900, 100), List.of());

        assertThat(report.mismatches()).containsExactly(Mismatch.ENTRIES_CHANGED_AFTER_CLOSE);
    }

    @Test
    void a_statement_with_lines_still_in_flight_is_a_mismatch() {
        var gateway = gateway(1000, 900, 100);

        var report = Verdicts.verdictOf(
            1000, gateway, ledger(900, 100), check(true), books(true), client(true, false, NO_IDS), stats(1000, 900, 100), List.of());

        assertThat(report.mismatches()).containsExactly(Mismatch.STATEMENT_NOT_FINAL);
    }

    @Test
    @DisplayName("numbers from the events that are missing or behind are noted and do not change the verdict")
    void numbers_from_the_events_that_are_missing_or_behind_are_noted_and_do_not_change_the_verdict() {
        var gateway = gateway(1000, 900, 100);

        var missing = Verdicts.verdictOf(1000, gateway, ledger(900, 100), check(true), books(true), client(), null, List.of());
        var behind = Verdicts.verdictOf(1000, gateway, ledger(900, 100), check(true), books(true), client(), stats(1000, 899, 100), List.of());

        assertThat(missing.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(missing.notes()).containsExactly(PeriodNote.REPORTING_UNAVAILABLE);
        assertThat(behind.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(behind.notes()).containsExactly(PeriodNote.PROJECTION_BEHIND_BOOKS);
    }
}
