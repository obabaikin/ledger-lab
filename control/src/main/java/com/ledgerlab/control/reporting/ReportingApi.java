package com.ledgerlab.control.reporting;

/** Control's model of the reporting service's internal API, checked against reporting/openapi.json. */
public final class ReportingApi {

    public static final String STATS_PATH = "/internal/periods/{periodStart}/stats";

    private ReportingApi() {
    }
}
