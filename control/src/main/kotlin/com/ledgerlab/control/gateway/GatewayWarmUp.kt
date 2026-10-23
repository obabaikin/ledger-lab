package com.ledgerlab.control.gateway

import com.ledgerlab.control.metrics.GatewayMetrics
import com.ledgerlab.control.metrics.PrometheusClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Whether every gateway instance finished its warm-up: right after a deploy all services start cold together, and a
 * run admitted then met a wave of 503 busy (measured on the server). A killed instance's series goes stale and drops out.
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
        const val ALL_WARM = "min(${GatewayMetrics.WARM_UP_COMPLETE})"
        const val WARM = 1.0
    }
}
