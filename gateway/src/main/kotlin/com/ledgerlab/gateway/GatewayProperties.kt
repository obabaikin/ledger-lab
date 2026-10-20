package com.ledgerlab.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gateway")
data class GatewayProperties(
    val transfersTopic: String,
    val resultsTopic: String,
    val outbox: Outbox,
    val consumer: Consumer,
    val maxWait: Duration,
    val retryAfter: Duration,
    /** How long closing a period waits for inserts in flight; meanwhile new submissions of the run wait too. */
    val closeLockTimeout: Duration,
    val admission: Admission,
    val warmUp: WarmUp,
) {
    /** Up to [rounds] rounds of the submission path before the gateway takes traffic, for at most [duration]. */
    data class WarmUp(val rounds: Int, val duration: Duration)
    /** At most [maxConcurrent] submissions work with the database; one more waits up to [wait], then is refused. */
    data class Admission(val maxConcurrent: Int, val wait: Duration)
    data class Outbox(val batchSize: Int, val sendTimeout: Duration)
    data class Consumer(val retryBackoff: Duration)
}
