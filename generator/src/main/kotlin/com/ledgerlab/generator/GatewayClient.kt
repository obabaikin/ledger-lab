package com.ledgerlab.generator

import com.ledgerlab.generator.bank.ApiError
import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.Statement
import com.ledgerlab.generator.bank.StatementLine
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferResponse
import com.ledgerlab.generator.bank.TransferStatus
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.cancellation.CancellationException

object GatewayApi {
    const val TRANSFERS_PATH = "/v1/transfers"
    const val WAIT_PARAM = "wait"
    const val AFTER_PARAM = "after"
    const val LIMIT_PARAM = "limit"
    const val STATEMENT_MAX_PAGE = 1_000
    /** The error of a 503 the gateway gives before it takes the transfer; any other 5xx leaves it unknown. */
    const val BUSY_ERROR = "busy"

    const val STATEMENT_PATH = "/v1/runs/{runId}/statement"
    private const val RUN_ID_VARIABLE = "{runId}"

    fun statementPath(runId: UUID) = STATEMENT_PATH.replace(RUN_ID_VARIABLE, runId.toString())
}

class PeriodNotClosedException : ConflictException(GeneratorErrors.PERIOD_NOT_CLOSED)

class GatewayUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class BadGatewayAnswerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

const val FIRST_ATTEMPT = 1

/** Outcomes of the client contract, ADR 0006. */
enum class SubmissionOutcome {
    ACKNOWLEDGED,
    FAILED,
    UNKNOWN,
}

data class SubmissionResult(
    /** Null when the answer could not be read, e.g. a status this client does not know yet. */
    val status: TransferStatus?,
    val rejectReason: RejectReason?,
    val attempts: Int,
    val outcome: SubmissionOutcome,
    val latency: Duration,
    /** Attempts the gateway refused as busy: it did not take the transfer. */
    val refusals: Int = 0,
)

/** Retries reuse the transfer id, the idempotency key, so a retry after a timeout cannot create a second transfer. */
@Component
class GatewayClient(
    private val props: GeneratorProperties,
    private val objectMapper: ObjectMapper,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val unreadable = Counter.builder(UNREADABLE_METRIC)
        .description("2xx answers with a value this client does not know; the status code still decided the outcome, and a statement line is not compared")
        .register(registry)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .build()

    // A gateway one version ahead may send a status or reason this client does not know; the code still decides the outcome.
    private val responseReader: ObjectReader = objectMapper.readerFor(TransferResponse::class.java)
        .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
    private val statementReader: ObjectReader = objectMapper.readerFor(Statement::class.java)
        .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)

    fun statement(runId: UUID, after: UUID?): Statement {
        val query = "?${GatewayApi.LIMIT_PARAM}=${GatewayApi.STATEMENT_MAX_PAGE}" + (after?.let { "&${GatewayApi.AFTER_PARAM}=$it" } ?: "")
        val request = HttpRequest.newBuilder(URI.create(props.gatewayUrl + GatewayApi.statementPath(runId) + query))
            .timeout(props.requestTimeout)
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw GatewayUnavailableException("No answer from the gateway to the statement of run $runId", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GatewayUnavailableException("Interrupted while reading the statement of run $runId", e)
        }
        val code = HttpStatusCode.valueOf(response.statusCode())
        return when {
            code.is2xxSuccessful -> readStatement(response.body())
                ?: throw BadGatewayAnswerException("Unreadable statement page of run $runId")
            code == HttpStatus.CONFLICT -> throw PeriodNotClosedException()
            code.is5xxServerError -> throw GatewayUnavailableException("The gateway answered ${code.value()} to the statement of run $runId")
            else -> throw BadGatewayAnswerException("The gateway answered ${code.value()} to the statement of run $runId")
        }
    }

    private fun readStatement(body: String): Statement? {
        val tree = runCatching { objectMapper.readTree(body) }.getOrNull()
        val page = tree?.let { runCatching { statementReader.readValue<Statement?>(it) }.getOrNull() } ?: return null
        val lines = tree.get(Statement::items.name).values()
        val lost = page.items.zip(lines).filter { (line, node) ->
            line.status == null || (line.rejectReason == null && node.hasValue(StatementLine::rejectReason.name))
        }.map { (line, _) -> line.transferId }
        if (lost.isNotEmpty()) {
            unreadable.increment()
            log.warn("Statement page with {} lines this client cannot read in full, the first: {}", lost.size, lost.take(LOGGED_IDS))
        }
        return page
    }

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
        var refusals = 0
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
                    refusals = refusals,
                )
            }
            val busy = response != null && code == HttpStatus.SERVICE_UNAVAILABLE && isBusy(response.body())
            if (busy) refusals++ else if (response != null) mayHaveArrived = true
            val givenUp = SubmissionResult(
                null, null, attempt,
                if (mayHaveArrived) SubmissionOutcome.UNKNOWN else SubmissionOutcome.FAILED,
                elapsed,
                refusals,
            )
            // Checked before the backoff so a stop does not wait for it, and after it because the
            // stop may come while this request is sleeping.
            if (attempt >= props.maxAttempts || !canRetry()) return givenUp
            delay(maxOf(backoff(attempt), if (busy) retryAfterMillis(response) else 0))
            if (!canRetry()) return givenUp
        }
    }

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

    private fun isBusy(body: String): Boolean =
        runCatching { objectMapper.readValue(body, ApiError::class.java).error == GatewayApi.BUSY_ERROR }.getOrDefault(false)

    private fun retryAfterMillis(response: HttpResponse<String>?): Long =
        response?.headers()?.firstValue(HttpHeaders.RETRY_AFTER)?.orElse(null)?.toLongOrNull()
            ?.let { Duration.ofSeconds(it).toMillis() } ?: 0

    private fun JsonNode.hasValue(field: String): Boolean = get(field)?.isNull == false

    /** Jitter spreads the retries of many clients that failed together, so a recovering gateway is not hit at once. */
    private fun backoff(attempt: Int): Long {
        val base = props.retryBackoff.toMillis() shl (attempt - FIRST_ATTEMPT)
        return (base * ThreadLocalRandom.current().nextDouble(JITTER_MIN, JITTER_MAX)).toLong()
    }

    companion object {
        const val UNREADABLE_METRIC = "generator.gateway.unreadable"
        private const val LOGGED_IDS = 10

        private const val JITTER_MIN = 0.5
        private const val JITTER_MAX = 1.5
    }
}
