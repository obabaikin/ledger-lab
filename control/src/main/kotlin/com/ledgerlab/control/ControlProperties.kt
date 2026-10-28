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
    val invites: Invites,
    val turnstile: Turnstile,
    val mail: Mail,
    val site: Site,
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
        /** At most this many starts wait for the queue's lock at once; one more waits up to [startWait], then is refused. */
        val maxConcurrentStarts: Int,
        val startWait: Duration,
    ) {
        init {
            require(tpsOptions.all { it <= maxTotalTps }) { "a run of ${tpsOptions.max()} TPS would never fit max-total-tps $maxTotalTps" }
            require(defaultTps in tpsOptions) { "default-tps $defaultTps is not one of tps-options $tpsOptions" }
        }

        val durationSecondsOptions: List<Int> get() = durationOptions.map { it.toSeconds().toInt() }
    }

    data class Accounts(val count: Int, val openingBalanceCents: Long, val blocked: Int, val dailyLimitCents: Long)

    data class Ledger(val baseUrl: String, val transferPartitions: Int, val reconciliationTimeout: Duration)

    data class Gateway(val baseUrl: String, val summaryTimeout: Duration)

    data class Generator(val baseUrl: String, val reconciliationTimeout: Duration)

    data class Services(val requestTimeout: Duration)

    data class Cleanup(val interval: Duration, val retention: Duration)

    data class Reporting(val baseUrl: String, val catchUpWait: Duration, val finalStatsTimeout: Duration)

    data class Prometheus(val baseUrl: String, val requestTimeout: Duration)

    data class Chaos(
        val enabled: Boolean,
        val minInterval: Duration,
        val requestTimeout: Duration,
        val ledgerInstances: List<LedgerInstance> = emptyList(),
    )

    data class LedgerInstance(val name: String, val url: String)

    data class Invites(val email: InviteEmail)

    data class InviteEmail(
        val linksPerAddress: Int,
        val dailyMails: Int,
        val window: Duration,
        val lifetime: Duration,
        val runsPerHour: Int,
        val retention: Duration,
    )

    /** An empty [secret] switches the check off, as locally and in tests. */
    data class Turnstile(val siteKey: String, val secret: String, val baseUrl: String, val requestTimeout: Duration)

    /** An empty [apiKey] logs each mail as not sent instead of sending it. */
    data class Mail(
        val baseUrl: String,
        val apiKey: String,
        val apiSecret: String,
        val from: String,
        val sendInterval: Duration,
        val batchSize: Int,
        val maxAttempts: Int,
        val firstRetry: Duration,
        val requestTimeout: Duration,
    )

    /** [baseUrl] is the page's address in the mailed links; the operator's details fill the imprint and privacy pages. */
    data class Site(val baseUrl: String, val operatorName: String, val operatorAddress: String, val contactEmail: String)
}
