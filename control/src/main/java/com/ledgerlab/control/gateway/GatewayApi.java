package com.ledgerlab.control.gateway;

/** Control's model of the gateway's internal API, checked against gateway/openapi-internal.json. */
public final class GatewayApi {
    public static final String SUMMARY_PATH = "/internal/runs/{runId}/summary";
    public static final String CLOSE_PATH = "/internal/runs/{runId}/close";
    public static final String RUN_PATH = "/internal/runs/{runId}";

    private GatewayApi() {
    }
}
