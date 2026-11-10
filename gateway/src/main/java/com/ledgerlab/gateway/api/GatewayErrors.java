package com.ledgerlab.gateway.api;

/** Error texts of the gateway's API; clients tell the cases apart by status code, the text names it. */
public final class GatewayErrors {
    public static final String PERIOD_CLOSED = "period closed";
    public static final String PERIOD_NOT_CLOSED = "period not closed";
    public static final String CLOSE_LOCK_TIMEOUT = "inserts in flight did not finish in time";
    public static final String BAD_REQUEST = "bad request";
    public static final String CONFLICT = "conflict";
    public static final String BUSY = "busy";

    private GatewayErrors() {
    }
}
