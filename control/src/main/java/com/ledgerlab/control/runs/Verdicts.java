package com.ledgerlab.control.runs;

import com.ledgerlab.control.gateway.RunSummary;
import com.ledgerlab.control.ledger.ReconciliationReport;
import com.ledgerlab.control.reporting.RunStats;
import java.util.ArrayList;
import java.util.List;

public final class Verdicts {

    private Verdicts() {
    }

    public static boolean projectionMatchesBooks(RunStats stats, RunSummary gateway, ReconciliationReport ledger) {
        return stats.totals().commandsSeen() == gateway.accepted() && stats.totals().posted() == ledger.postedTransfers()
            && stats.totals().rejected() == ledger.rejectedTransfers();
    }

    public static boolean drainFinished(RunSummary gateway, ReconciliationReport ledger) {
        return gateway.posted() + gateway.rejected() == gateway.accepted()
            && gateway.accepted() == ledger.postedTransfers() + ledger.rejectedTransfers();
    }

    /** Ledger and gateway only learn of transfers the gateway accepted, so more than that is never lag. */
    public static boolean overcounted(RunSummary gateway, ReconciliationReport ledger) {
        return ledger.postedTransfers() + ledger.rejectedTransfers() > gateway.accepted()
            || gateway.posted() + gateway.rejected() > gateway.accepted();
    }

    public static RunReport verdictOf(RunSummary gateway, ReconciliationReport ledger, ClientSide client) {
        return verdictOf(gateway, ledger, client, RunMeasurements.none());
    }

    public static RunReport verdictOf(RunSummary gateway, ReconciliationReport ledger, ClientSide client, RunMeasurements measurements) {
        var drained = drainFinished(gateway, ledger);
        var overcounted = overcounted(gateway, ledger);
        // Before the drain ends the gateway's counts trail the ledger's: a difference then is lag, not a break.
        Boolean bankViewsAgree;
        if (overcounted) {
            bankViewsAgree = false;
        } else if (drained) {
            bankViewsAgree = gateway.posted() == ledger.postedTransfers() && gateway.rejected() == ledger.rejectedTransfers()
                && gateway.rejectionsByReason().equals(ledger.rejectionsByReason());
        } else {
            bankViewsAgree = null;
        }
        var clientReport = client instanceof ClientSide.Reconciled(var report) ? report : null;
        var reasons = new ArrayList<String>();
        if (!drained && !overcounted) {
            reasons.add(RunOutcomes.drainTimedOut(
                ledger.postedTransfers() + ledger.rejectedTransfers(), gateway.posted() + gateway.rejected(), gateway.accepted()));
        }
        if (client instanceof ClientSide.Missing(var reason)) {
            reasons.add(reason);
        }
        if (clientReport != null && !clientReport.statementFinal()) {
            reasons.add(RunOutcomes.STATEMENT_NOT_FINAL);
        }
        var stats = measurements.stats();
        var projectionMatches = stats == null ? null : (Boolean) projectionMatchesBooks(stats, gateway, ledger);
        List<String> notes;
        if (stats == null) {
            notes = List.of(RunNotes.REPORTING_UNAVAILABLE);
        } else if (!projectionMatchesBooks(stats, gateway, ledger)) {
            notes = List.of(RunNotes.projectionBehindBooks(stats, gateway, ledger));
        } else {
            notes = List.of();
        }
        RunResult result;
        if (!ledger.balanced() || Boolean.FALSE.equals(bankViewsAgree) || (clientReport != null && !clientReport.balanced())) {
            result = RunResult.BREAKS;
        } else if (!reasons.isEmpty()) {
            result = RunResult.INCOMPLETE;
        } else {
            result = RunResult.BALANCED;
        }
        return new RunReport(
            result,
            reasons,
            ledger.balanced(),
            ledger.failedChecks(),
            bankViewsAgree,
            clientReport == null ? null : clientReport.balanced(),
            clientReport == null ? null : clientReport.statementFinal(),
            clientReport == null ? null : clientReport.journal(),
            new GatewayCounts(gateway.accepted(), gateway.published(), gateway.posted(), gateway.rejected()),
            new LedgerCounts(
                ledger.postedTransfers(), ledger.rejectedTransfers(),
                ledger.openingTotalCents(), ledger.balanceTotalCents(), ledger.debitsCents(), ledger.creditsCents()),
            gateway.latencyP50Ms(),
            gateway.latencyP99Ms(),
            ledger.rejectionsByReason(),
            clientReport == null ? null : clientReport.breaks(),
            clientReport == null ? null : clientReport.unknownResolved(),
            measurements.clientLatencyP50Ms(),
            measurements.clientLatencyP99Ms(),
            stats,
            projectionMatches,
            measurements.ledgerDuplicatesAtLeast(),
            notes,
            measurements.chaos());
    }
}
