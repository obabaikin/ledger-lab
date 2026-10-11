package com.ledgerlab.control

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("control")
data class ControlProperties(val runs: Runs, val queue: Queue, val ledger: Ledger) {
    data class Runs(
        val maxActive: Int,
        val maxQueued: Int,
        val queueTimeout: Duration,
        val tpsOptions: List<Int>,
        val durationOptions: List<Duration>,
    ) {
        val durationSecondsOptions: List<Int> get() = durationOptions.map { it.toSeconds().toInt() }
    }

    data class Queue(val pollInterval: Duration)

    data class Ledger(val baseUrl: String, val readinessTimeout: Duration)
}
