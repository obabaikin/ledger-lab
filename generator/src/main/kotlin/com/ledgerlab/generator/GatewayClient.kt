package com.ledgerlab.generator

import com.ledgerlab.common.TransferCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.cancellation.CancellationException

/** Everything the generator knows about the gateway's public API. */
object GatewayApi {
    const val TRANSFERS_PATH = "/v1/transfers"
    const val STATUS_POSTED = "POSTED"
    const val STATUS_REJECTED = "REJECTED"
    val FINAL_STATUSES = setOf(STATUS_POSTED, STATUS_REJECTED)
}

/** How a client reads the gateway's answers (client contract, ADR 0006). */
enum class SubmissionOutcome {
    /** 2xx: the gateway has the transfer. */
    ACKNOWLEDGED,
    /** 4xx, or no attempt reached the gateway: the transfer was not accepted. */
    FAILED,
    /** 5xx or no answer on some attempt and no answer since: the transfer may have been accepted. */
    UNKNOWN,
}

/** What the generator learns from one submission, after retries. */
data class SubmissionResult(
    val status: String?,
    val rejectReason: String?,
    val attempts: Int,
    val outcome: SubmissionOutcome,
    val latency: Duration,
)

/**
 * The only thing the generator knows about the bank: the public API of the gateway.
 * Retries reuse the same transfer id, which is the idempotency key, so a retry after a
 * timeout can never create a second transfer.
 */
@Component
class GatewayClient(private val props: GeneratorProperties, private val objectMapper: ObjectMapper) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .build()

    suspend fun submit(
        command: TransferCommand,
        wait: Duration?,
        canRetry: () -> Boolean = { true },
        sends: SendRecorder = SendRecorder.NONE,
    ): SubmissionResult {
        val url = props.gatewayUrl + GatewayApi.TRANSFERS_PATH + (wait?.let { "?wait=$it" } ?: "")
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "transferId" to command.transferId,
                "runId" to command.runId,
                "fromAccount" to command.fromAccount,
                "toAccount" to command.toAccount,
                "amountCents" to command.amountCents,
            ),
        )
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout((wait ?: Duration.ZERO) + props.requestTimeout)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val started = System.nanoTime()
        var attempt = 0
        var mayHaveArrived = false
        while (true) {
            attempt++
            val sendStarted = System.nanoTime()
            // Stays NO_RESPONSE if something unexpected is thrown: the request may have left by then.
            var sendResult = SendResult.NO_RESPONSE
            var response: HttpResponse<String>? = null
            try {
                response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                sendResult = SendResult.RESPONSE
            } catch (e: IOException) {
                // Without a connection the request never left; any other I/O error may come after the gateway committed.
                if (e is ConnectException || e is HttpConnectTimeoutException) {
                    sendResult = SendResult.NOT_CONNECTED
                } else {
                    mayHaveArrived = true
                }
            } catch (e: CancellationException) {
                sendResult = SendResult.CANCELLED
                throw e
            } finally {
                sends.record(attempt, sendStarted, System.nanoTime(), sendResult, response?.statusCode())
            }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            if (response != null && response.statusCode() !in 500..599) {
                val body = runCatching { objectMapper.readValue(response.body(), Map::class.java) }.getOrNull()
                return SubmissionResult(
                    status = body?.get("status") as String?,
                    rejectReason = body?.get("rejectReason") as String?,
                    attempts = attempt,
                    outcome = if (response.statusCode() in 200..299) SubmissionOutcome.ACKNOWLEDGED else SubmissionOutcome.FAILED,
                    latency = elapsed,
                )
            }
            if (response != null) mayHaveArrived = true
            val givenUp = SubmissionResult(
                null, null, attempt,
                if (mayHaveArrived) SubmissionOutcome.UNKNOWN else SubmissionOutcome.FAILED,
                elapsed,
            )
            // Checked before the backoff so a stop does not wait for it, and after it because the
            // stop may come while this request is sleeping.
            if (attempt >= props.maxAttempts || !canRetry()) return givenUp
            delay(backoff(attempt))
            if (!canRetry()) return givenUp
        }
    }

    /** Jitter spreads the retries of many clients that failed together, so a recovering gateway is not hit at once. */
    private fun backoff(attempt: Int): Long {
        val base = props.retryBackoff.toMillis() shl (attempt - 1)
        return (base * ThreadLocalRandom.current().nextDouble(0.5, 1.5)).toLong()
    }
}
