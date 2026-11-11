package com.ledgerlab.control.gateway;

/** Control's model of the gateway's internal API, checked against gateway/openapi-internal.json. */
public final class GatewayApi {

    public static final String SUMMARY_PATH = "/internal/periods/{periodStart}/summary";
    public static final String CLOSE_PATH = "/internal/periods/{periodStart}/close";

    private GatewayApi() {
    }
}
