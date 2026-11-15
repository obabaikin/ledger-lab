package com.ledgerlab.control.ledger;

/** Control's model of the ledger's internal API, checked against ledger-service/openapi.json. */
public final class LedgerApi {

    public static final String RECONCILIATION_PATH = "/internal/periods/{periodStart}/reconciliation";
    public static final String CHECK_PATH = "/internal/periods/{periodStart}/check";
    public static final String BOOKS_PATH = "/internal/books";

    private LedgerApi() {
    }
}
