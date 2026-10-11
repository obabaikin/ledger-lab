package com.ledgerlab.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gateway")
data class GatewayProperties(
    val transfersTopic: String,
    val resultsTopic: String,
    val outbox: Outbox,
    val consumer: Consumer,
    /** Upper bound for how long a client may ask to wait for the result. */
    val maxWait: Duration,
    /** Sent with every 202 as the hint when to ask for the result. */
    val retryAfter: Duration,
    /** How long closing a period waits for inserts in flight; meanwhile new submissions of the run wait too. */
    val closeLockTimeout: Duration,
) {
    data class Outbox(val publishInterval: Duration, val batchSize: Int, val sendTimeout: Duration)
    data class Consumer(val retryBackoff: Duration)
}
