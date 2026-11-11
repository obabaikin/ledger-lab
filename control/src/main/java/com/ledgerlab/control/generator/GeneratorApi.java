package com.ledgerlab.control.generator;

/** Control's model of the generator's internal API, checked against generator/openapi.json. */
public final class GeneratorApi {

    public static final String LOAD_PATH = "/internal/load";
    public static final String MINUTES_PATH = "/internal/load/minutes";
    public static final String MINUTES_AFTER = "after";
    public static final String RECONCILIATION_PATH = "/internal/periods/{periodStart}/reconciliation";

    private GeneratorApi() {
    }
}
