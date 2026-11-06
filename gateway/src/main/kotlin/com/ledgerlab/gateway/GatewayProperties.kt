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
    val waitRead: WaitRead,
    val retryAfter: Duration,
    /** How long closing a period waits for inserts in flight; meanwhile new submissions of the run wait too. */
    val closeLockTimeout: Duration,
    val inflight: Inflight,
    val admission: Admission,
    val accept: Accept,
    val warmUp: WarmUp,
) {
    /**
     * Up to [rounds] rounds of the submission path. The gateway takes traffic after at most [duration]; the rounds go
     * on behind it for at most [maxDuration], and only then does it report itself warm (control admits runs after that).
     */
    data class WarmUp(val rounds: Int, val duration: Duration, val maxDuration: Duration)
    /** At most [maxConcurrent] submissions are open at once, waiting for the result included; one more is refused. */
    data class Inflight(val maxConcurrent: Int)
    /** At most [maxConcurrent] submissions insert at once; one more waits up to [wait], then is refused. */
    data class Admission(val maxConcurrent: Int, val wait: Duration)
    /** Each of [writers] commits up to [batchSize] submissions per transaction; a request waits up to [answerTimeout]. */
    data class Accept(val batchSize: Int, val writers: Int, val answerTimeout: Duration)
    /** A woken request reads its row after [firstPause], then after twice the last pause, at most [maxReads] times. */
    data class WaitRead(val firstPause: Duration, val maxReads: Int)
    data class Outbox(val batchSize: Int, val sendTimeout: Duration)
    data class Consumer(val retryBackoff: Duration)
}
