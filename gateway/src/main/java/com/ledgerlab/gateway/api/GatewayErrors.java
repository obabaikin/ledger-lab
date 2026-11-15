package com.ledgerlab.gateway.api;

/** Error codes of the gateway's API. */
public final class GatewayErrors {
    public static final String UNKNOWN_PERIOD = "unknown-period";
    public static final String PERIOD_NOT_CLOSED = "period-not-closed";
    public static final String CLOSE_LOCK_TIMEOUT = "close-lock-timeout";
    public static final String INVALID_LIMIT = "invalid-limit";
    public static final String INVALID_CURSOR = "invalid-cursor";
    public static final String IDEMPOTENCY_CONFLICT = "idempotency-conflict";
    public static final String BUSY = "busy";
    public static final String STOPPING = "stopping";

    private GatewayErrors() {
    }
}
