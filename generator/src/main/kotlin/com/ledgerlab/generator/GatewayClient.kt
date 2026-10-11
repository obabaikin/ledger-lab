package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferResponse
import com.ledgerlab.generator.bank.TransferStatus
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.cfg.EnumFeature
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
    const val WAIT_PARAM = "wait"
}

/** Number of the first send of a transfer; retries and duplicates count on from it. */
const val FIRST_ATTEMPT = 1

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
    /** Null when the answer could not be read, e.g. a status this client does not know yet. */
    val status: TransferStatus?,
    val rejectReason: RejectReason?,
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
class GatewayClient(
    private val props: GeneratorProperties,
    private val objectMapper: ObjectMapper,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val unreadable = Counter.builder(UNREADABLE_METRIC)
        .description("2xx answers with a value this client does not know; the status code still decided the outcome")
        .register(registry)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .build()

    // A gateway one version ahead may send a status or reason this client does not know; the code still decides the outcome.
    private val responseReader: ObjectReader = objectMapper.readerFor(TransferResponse::class.java)
        .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)

    suspend fun submit(
        transfer: PlannedTransfer,
        wait: Duration?,
        canRetry: () -> Boolean = { true },
        sends: SendRecorder = SendRecorder.NONE,
    ): SubmissionResult {
        val url = props.gatewayUrl + GatewayApi.TRANSFERS_PATH + (wait?.let { "?${GatewayApi.WAIT_PARAM}=$it" } ?: "")
        val payload = objectMapper.writeValueAsString(
            SubmitTransferRequest(transfer.transferId, transfer.runId, transfer.fromAccount, transfer.toAccount, transfer.amountCents),
        )
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout((wait ?: Duration.ZERO) + props.requestTimeout)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
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
            val code = response?.let { HttpStatusCode.valueOf(it.statusCode()) }
            if (response != null && code?.is5xxServerError == false) {
                val body = read(response.body(), code.is2xxSuccessful)
                return SubmissionResult(
                    status = body?.status,
                    rejectReason = body?.rejectReason,
                    attempts = attempt,
                    outcome = if (code.is2xxSuccessful) SubmissionOutcome.ACKNOWLEDGED else SubmissionOutcome.FAILED,
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

    /** Null when the body cannot be read; a 2xx that cannot be read in full is logged and counted. */
    private fun read(body: String, acknowledged: Boolean): TransferResponse? {
        val tree = runCatching { objectMapper.readTree(body) }.getOrNull()
        val response = tree?.let { runCatching { responseReader.readValue<TransferResponse>(it) }.getOrNull() }
        val reasonLost = response != null && response.rejectReason == null && tree.hasValue(TransferResponse::rejectReason.name)
        if (acknowledged && (response == null || reasonLost)) {
            unreadable.increment()
            log.warn("Gateway answer with a value this client does not know: {}", body)
        }
        return response
    }

    private fun JsonNode.hasValue(field: String): Boolean = get(field)?.isNull == false

    /** Jitter spreads the retries of many clients that failed together, so a recovering gateway is not hit at once. */
    private fun backoff(attempt: Int): Long {
        val base = props.retryBackoff.toMillis() shl (attempt - FIRST_ATTEMPT)
        return (base * ThreadLocalRandom.current().nextDouble(JITTER_MIN, JITTER_MAX)).toLong()
    }

    companion object {
        const val UNREADABLE_METRIC = "generator.gateway.unreadable"

        /** Each pause is the base times a random factor in [JITTER_MIN, JITTER_MAX) (ADR 0006). */
        private const val JITTER_MIN = 0.5
        private const val JITTER_MAX = 1.5
    }
}
