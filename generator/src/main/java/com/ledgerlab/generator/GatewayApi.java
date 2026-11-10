package com.ledgerlab.generator;

import java.util.UUID;

public final class GatewayApi {
    public static final String TRANSFERS_PATH = "/v1/transfers";
    public static final String WAIT_PARAM = "wait";
    public static final String AFTER_PARAM = "after";
    public static final String LIMIT_PARAM = "limit";
    public static final int STATEMENT_MAX_PAGE = 1_000;
    /** The error of a 503 the gateway gives before it takes the transfer; any other 5xx leaves it unknown. */
    public static final String BUSY_ERROR = "busy";

    public static final String STATEMENT_PATH = "/v1/runs/{runId}/statement";
    private static final String RUN_ID_VARIABLE = "{runId}";

    private GatewayApi() {
    }

    public static String statementPath(UUID runId) {
        return STATEMENT_PATH.replace(RUN_ID_VARIABLE, runId.toString());
    }
}
