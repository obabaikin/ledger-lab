package com.ledgerlab.control.runs;

import com.ledgerlab.control.gateway.RunSummary;
import com.ledgerlab.control.ledger.ReconciliationReport;
import com.ledgerlab.control.reporting.RunStats;

/** What the report could not show; none of it decides the verdict. */
public final class RunNotes {
    public static final String REPORTING_UNAVAILABLE = "reporting unavailable: no numbers from the bank's events";

    private RunNotes() {
    }

    public static String projectionBehindBooks(RunStats stats, RunSummary gateway, ReconciliationReport ledger) {
        return "projection does not match the books: commands " + stats.totals().commandsSeen() + " of " + gateway.accepted() + ", "
            + "posted " + stats.totals().posted() + " of " + ledger.postedTransfers() + ", rejected " + stats.totals().rejected()
            + " of " + ledger.rejectedTransfers();
    }
}
