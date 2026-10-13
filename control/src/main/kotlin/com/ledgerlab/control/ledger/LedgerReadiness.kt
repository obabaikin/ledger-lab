package com.ledgerlab.control.ledger

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.metrics.LedgerMetrics
import com.ledgerlab.control.metrics.PrometheusClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

/** A failed scrape makes an instance's series stale, so the sum counts only instances that answered (ADR 0012). */
@Component
class LedgerReadiness(private val prometheus: PrometheusClient, props: ControlProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val partitions = props.ledger.transferPartitions

    @Volatile private var lastHeld: Int? = null

    fun isReady(): Boolean {
        val held = prometheus.query(HELD_PARTITIONS)?.singleOrNull()?.number?.roundToInt()
        if (held != lastHeld) log.info("Ledger instances hold {} of {} partitions; runs start only at all of them", held, partitions)
        lastHeld = held
        return held == partitions
    }

    private companion object {
        const val HELD_PARTITIONS = "sum(${LedgerMetrics.ASSIGNED_PARTITIONS})"
    }
}
