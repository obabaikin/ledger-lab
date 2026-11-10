package com.ledgerlab.control.ledger;

/** Control's model of the ledger's internal API, checked against ledger-service/openapi.json. */
public final class LedgerApi {
    public static final String ACCOUNTS_PATH = "/internal/runs/{runId}/accounts";
    public static final String RECONCILIATION_PATH = "/internal/runs/{runId}/reconciliation";
    public static final String RUN_PATH = "/internal/runs/{runId}";

    private LedgerApi() {
    }
}
