package com.ledgerlab.control

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("control")
data class ControlProperties(
    val runs: Runs,
    val ledger: Ledger,
    val gateway: Gateway,
    val generator: Generator,
    val services: Services,
    val cleanup: Cleanup,
    val reporting: Reporting,
    val prometheus: Prometheus,
    val chaos: Chaos,
) {
    data class Runs(
        val maxActive: Int,
        val maxQueued: Int,
        val queueTimeout: Duration,
        val tpsOptions: List<Int>,
        val defaultTps: Int,
        val durationOptions: List<Duration>,
        val setupTimeout: Duration,
        val runningGrace: Duration,
        val drainStallTimeout: Duration,
        val drainTimeout: Duration,
        val accounts: Accounts,
        val maxTotalTps: Int,
    ) {
        init {
            require(tpsOptions.all { it <= maxTotalTps }) { "a run of ${tpsOptions.max()} TPS would never fit max-total-tps $maxTotalTps" }
            require(defaultTps in tpsOptions) { "default-tps $defaultTps is not one of tps-options $tpsOptions" }
        }

        val durationSecondsOptions: List<Int> get() = durationOptions.map { it.toSeconds().toInt() }
    }

    data class Accounts(val count: Int, val openingBalanceCents: Long, val blocked: Int, val dailyLimitCents: Long)

    data class Ledger(val baseUrl: String, val transferPartitions: Int)

    data class Gateway(val baseUrl: String)

    data class Generator(val baseUrl: String, val reconciliationTimeout: Duration)

    data class Services(val requestTimeout: Duration)

    data class Cleanup(val interval: Duration, val retention: Duration)

    data class Reporting(val baseUrl: String, val catchUpWait: Duration)

    data class Prometheus(val baseUrl: String, val requestTimeout: Duration)

    data class Chaos(
        val enabled: Boolean,
        val minInterval: Duration,
        val requestTimeout: Duration,
        val ledgerInstances: List<LedgerInstance> = emptyList(),
    )

    data class LedgerInstance(val name: String, val url: String)
}
