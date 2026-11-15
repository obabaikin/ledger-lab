package com.ledgerlab.control.periods;

public enum PeriodCall {
    GATEWAY_CLOSE("gateway.close"),
    GATEWAY_SUMMARY("gateway.summary"),
    LEDGER_RECONCILIATION("ledger.reconciliation"),
    LEDGER_REQUEST_CHECK("ledger.request-check"),
    LEDGER_CHECK("ledger.check"),
    LEDGER_BOOKS("ledger.books"),
    REPORTING_STATS("reporting.stats"),
    GENERATOR_START_RECONCILIATION("generator.start-reconciliation"),
    GENERATOR_RECONCILIATION("generator.reconciliation");

    public static final String METRIC = "control.period.calls";
    public static final String CALL_TAG = "call";

    private final String tag;

    PeriodCall(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
