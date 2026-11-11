package com.ledgerlab.generator;

import java.time.Instant;
import java.util.UUID;

public final class GatewayApi {

    public static final String TRANSFERS_PATH = "/v2/transfers";
    public static final String TRANSFER_PATH = TRANSFERS_PATH + "/{transferId}";
    public static final String WAIT_PARAM = "wait";
    public static final String AFTER_PARAM = "after";
    public static final String LIMIT_PARAM = "limit";
    public static final int STATEMENT_MAX_PAGE = 1_000;
    /** Errors of a 503 the gateway gives before it takes the transfer; any other 5xx leaves it unknown. */
    public static final String BUSY_ERROR = "busy";
    public static final String STOPPING_ERROR = "stopping";
    public static final String STATEMENT_PATH = "/v2/periods/{periodStart}/statement";

    private static final String PERIOD_START_VARIABLE = "{periodStart}";
    private static final String TRANSFER_ID_VARIABLE = "{transferId}";

    private GatewayApi() {
    }

    public static String statementPath(Instant periodStart) {
        return STATEMENT_PATH.replace(PERIOD_START_VARIABLE, periodStart.toString());
    }

    public static String transferPath(UUID transferId) {
        return TRANSFER_PATH.replace(TRANSFER_ID_VARIABLE, transferId.toString());
    }
}
