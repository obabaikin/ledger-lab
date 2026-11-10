package com.ledgerlab.control.runs;

public final class RunOutcomes {
    public static final String SETUP_TIMED_OUT = "setup timed out";
    public static final String SETUP_REJECTED = "setup rejected";
    public static final String PERIOD_NOT_CLOSED = "period not closed";
    public static final String BANK_UNAVAILABLE = "bank unavailable";
    public static final String CLIENT_LOST = "client lost";
    public static final String CLIENT_RECONCILIATION_UNAVAILABLE = "client reconciliation unavailable";
    public static final String STATEMENT_NOT_FINAL = "statement not final";

    private RunOutcomes() {
    }

    public static String drainTimedOut(long ledgerApplied, long gatewayResults, long accepted) {
        return "drain timed out: ledger applied " + ledgerApplied + ", gateway results " + gatewayResults + ", of " + accepted;
    }

    public static String clientReconciliationFailed(String error) {
        return "client reconciliation failed: " + error;
    }
}
