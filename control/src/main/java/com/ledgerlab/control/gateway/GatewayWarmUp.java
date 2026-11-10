package com.ledgerlab.control.gateway;

import com.ledgerlab.control.metrics.GatewayMetrics;
import com.ledgerlab.control.metrics.PrometheusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Whether every gateway instance finished its warm-up: right after a deploy all services start cold together, and a
 * run admitted then met a wave of 503 busy (measured on the server). An instance Prometheus failed to scrape has no
 * warm-up series, so the counts differ and the gateway is not warm; a killed instance drops out of both.
 */
@Component
public class GatewayWarmUp {

    private static final String ALL_WARM = "min(" + GatewayMetrics.WARM_UP_COMPLETE + ") * "
        + "(count(" + GatewayMetrics.WARM_UP_COMPLETE + ") == bool count(up{job=\"" + GatewayMetrics.JOB + "\"}))";
    private static final double WARM = 1.0;

    private static final Logger log = LoggerFactory.getLogger(GatewayWarmUp.class);

    private final PrometheusClient prometheus;
    private volatile boolean lastAnswer = false;

    public GatewayWarmUp(PrometheusClient prometheus) {
        this.prometheus = prometheus;
    }

    public boolean check() throws InterruptedException {
        var samples = prometheus.query(ALL_WARM);
        var now = samples != null && samples.size() == 1 && samples.getFirst().number() == WARM;
        if (now != lastAnswer) {
            log.info("Gateway warmed up: {}; runs start only once it has", now);
        }
        lastAnswer = now;
        return now;
    }
}
