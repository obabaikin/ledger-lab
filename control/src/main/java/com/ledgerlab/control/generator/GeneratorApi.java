package com.ledgerlab.control.generator;

/** Control's model of the generator's internal API, checked against generator/openapi.json. */
public final class GeneratorApi {
    public static final String SIMULATIONS_PATH = "/internal/simulations";
    public static final String SIMULATION_PATH = "/internal/simulations/{runId}";
    public static final String STOP_PATH = "/internal/simulations/{runId}/stop";
    public static final String RECONCILIATION_PATH = "/internal/simulations/{runId}/reconciliation";
    public static final String JOURNAL_PATH = "/internal/simulations/{runId}/journal";
    public static final String RUN_STILL_ACTIVE = "run still active";

    public static final String REFUSED_DESCRIPTION = "Transfers the gateway refused as busy (503) at least once, retried or not; each counts once.";

    private GeneratorApi() {
    }
}
