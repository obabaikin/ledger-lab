package com.ledgerlab.control.metrics;

/** Control's model of Prometheus' HTTP API: the instant query and the part of its answer control reads. */
public final class PrometheusApi {
    public static final String QUERY_PATH = "/api/v1/query";
    public static final String SUCCESS = "success";

    private PrometheusApi() {
    }
}
