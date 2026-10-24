package com.ledgerlab.control.gateway

import com.ledgerlab.control.metrics.GatewayMetrics
import com.ledgerlab.control.metrics.PrometheusClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Whether every gateway instance finished its warm-up: right after a deploy all services start cold together, and a
 * run admitted then met a wave of 503 busy (measured on the server). An instance Prometheus failed to scrape has no
 * warm-up series, so the counts differ and the gateway is not warm; a killed instance drops out of both.
 */
@Component
class GatewayWarmUp(private val prometheus: PrometheusClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var lastAnswer = false

    fun check(): Boolean {
        val now = prometheus.query(ALL_WARM)?.singleOrNull()?.number == WARM
        if (now != lastAnswer) log.info("Gateway warmed up: {}; runs start only once it has", now)
        lastAnswer = now
        return now
    }

    private companion object {
        const val ALL_WARM = "min(${GatewayMetrics.WARM_UP_COMPLETE}) * " +
            "(count(${GatewayMetrics.WARM_UP_COMPLETE}) == bool count(up{job=\"${GatewayMetrics.JOB}\"}))"
        const val WARM = 1.0
    }
}
