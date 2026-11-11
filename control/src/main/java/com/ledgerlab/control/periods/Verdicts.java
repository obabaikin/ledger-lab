package com.ledgerlab.control.periods;

import com.ledgerlab.control.chaos.ChaosEvent;
import com.ledgerlab.control.gateway.PeriodSummary;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.ledger.Books;
import com.ledgerlab.control.ledger.LedgerReconciliation;
import com.ledgerlab.control.ledger.PeriodCheck;
import com.ledgerlab.control.reporting.PeriodStats;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class Verdicts {

    private Verdicts() {
    }

    public static boolean drained(PeriodSummary gateway, LedgerReconciliation ledger) {
        return gateway.posted() + gateway.rejected() == gateway.accepted()
            && gateway.accepted() == ledger.postedTransfers() + ledger.rejectedTransfers();
    }

    /** Ledger and gateway only learn of transfers the gateway accepted, so more than that is never lag. */
    public static boolean overcounted(PeriodSummary gateway, LedgerReconciliation ledger) {
        return ledger.postedTransfers() + ledger.rejectedTransfers() > gateway.accepted()
            || gateway.posted() + gateway.rejected() > gateway.accepted();
    }

    /** The gateway reads its totals and its reasons one after the other: results that arrive in between show in one of them only. */
    public static boolean reasonsSettled(PeriodSummary gateway) {
        return gateway.rejectionsByReason().values().stream().mapToLong(Long::longValue).sum() == gateway.rejected();
    }

    public static boolean projectionMatchesBooks(PeriodStats stats, PeriodSummary gateway, LedgerReconciliation ledger) {
        return stats.commandsSeen() == gateway.accepted() && stats.posted() == ledger.postedTransfers()
            && stats.rejected() == ledger.rejectedTransfers();
    }

    public static PeriodNumbers numbersOf(PeriodSummary gateway, LedgerReconciliation ledger, @Nullable PeriodStats stats) {
        return new PeriodNumbers(
            gateway.accepted(), ledger.postedTransfers(), ledger.rejectedTransfers(), stats == null ? 0 : stats.postedAmountCents(),
            ledger.rejectionsByReason());
    }

    public static PeriodReport verdictOf(
        long entryCount,
        PeriodSummary gateway,
        LedgerReconciliation ledger,
        PeriodCheck ledgerCheck,
        Books books,
        ClientReconciliationReport client,
        @Nullable PeriodStats stats,
        List<ChaosEvent> chaos
    ) {
        var mismatches = new ArrayList<Mismatch>();
        var overcounted = overcounted(gateway, ledger);
        if (overcounted) {
            mismatches.add(Mismatch.MORE_RESULTS_THAN_ACCEPTED);
        } else if (!drained(gateway, ledger)) {
            mismatches.add(Mismatch.NOT_DRAINED);
        }
        if (entryCount != gateway.accepted()) {
            mismatches.add(Mismatch.ENTRIES_CHANGED_AFTER_CLOSE);
        }
        var bankViewsAgree = gateway.posted() == ledger.postedTransfers() && gateway.rejected() == ledger.rejectedTransfers()
            && gateway.rejectionsByReason().equals(ledger.rejectionsByReason());
        // Before the drain ends the gateway's counts trail the ledger's: NOT_DRAINED says so already.
        if (!bankViewsAgree && mismatches.stream().noneMatch(it -> it == Mismatch.NOT_DRAINED)) {
            mismatches.add(Mismatch.BANK_VIEWS_DISAGREE);
        }
        if (!ledgerCheck.balanced()) {
            mismatches.add(Mismatch.LEDGER_CHECK_FAILED);
        }
        if (!books.balanced()) {
            mismatches.add(Mismatch.BOOKS_OUT_OF_BALANCE);
        }
        if (!client.balanced()) {
            mismatches.add(Mismatch.CLIENT_JOURNAL_DISAGREES);
        }
        if (!client.statementFinal()) {
            mismatches.add(Mismatch.STATEMENT_NOT_FINAL);
        }
        List<PeriodNote> notes;
        if (stats == null) {
            notes = List.of(PeriodNote.REPORTING_UNAVAILABLE);
        } else if (!projectionMatchesBooks(stats, gateway, ledger)) {
            notes = List.of(PeriodNote.PROJECTION_BEHIND_BOOKS);
        } else {
            notes = List.of();
        }
        return new PeriodReport(
            mismatches.isEmpty() ? Verdict.BALANCED : Verdict.MISMATCH,
            mismatches,
            new GatewayCounts(gateway.accepted(), gateway.published(), gateway.posted(), gateway.rejected(), entryCount),
            new LedgerCounts(ledger.postedTransfers(), ledger.rejectedTransfers(), ledger.poolPostings()),
            ledger.rejectionsByReason(),
            ledgerCheck,
            books,
            client,
            stats,
            notes,
            chaos);
    }
}
