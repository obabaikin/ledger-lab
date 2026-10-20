package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.Statement
import com.ledgerlab.generator.bank.StatementLine
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal stand-in for the gateway's POST /transfers, with an optional first-attempt failure, a refusal,
 * a slow answer, a slow answer to repeats of an id, a ledger rejection for waited requests, and a 200
 * with a body as a newer gateway might send it. Its statement lists every transfer it answered with a
 * 2xx, as the ledger has settled them, in pages of [statementPage].
 */
class FakeGateway(
    private val failFirstAttempt: Boolean = false,
    private val refuseWith: Int? = null,
    /** The first attempts of every transfer are refused as busy, as an overloaded gateway does. */
    private val busyAttempts: Int = 0,
    private val busyRetryAfterSeconds: Int = 0,
    private val responseDelay: Duration = Duration.ZERO,
    private val rejectReason: RejectReason? = null,
    private val repeatDelay: Duration = Duration.ZERO,
    private val rawAnswer: ((SubmitTransferRequest) -> String)? = null,
    private val periodClosed: Boolean = true,
    private val statementPage: Int = 1_000,
    /** Status code to fail the given statement page with (numbered from 1), or null to serve it. */
    private val statementFailure: (page: Int) -> Int? = { null },
    /** Served with 200 instead of the statement, as a broken gateway might. */
    private val rawStatement: String? = null,
    /** Changes a statement page (numbered from 1) before it is served, as a broken gateway might. */
    private val tamperStatement: (page: Int, Statement) -> Statement = { _, page -> page },
) : AutoCloseable {
    private val objectMapper = jacksonObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    val attemptsById = ConcurrentHashMap<String, AtomicInteger>()
    val requests = AtomicInteger()
    @Volatile var answersHeld: CountDownLatch? = null
    private val accepted = LinkedHashMap<UUID, StatementLine>()
    private val statementRequests = AtomicInteger()

    val url: String get() = "http://localhost:${server.address.port}"

    init {
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext(GatewayApi.TRANSFERS_PATH) { exchange ->
            requests.incrementAndGet()
            answersHeld?.await()
            val request = objectMapper.readValue<SubmitTransferRequest>(exchange.requestBody.readAllBytes())
            val id = request.transferId.toString()
            val attempt = attemptsById.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            val waited = exchange.requestURI.query?.contains("${GatewayApi.WAIT_PARAM}=") == true
            Thread.sleep(if (attempt > FIRST_ATTEMPT) repeatDelay else responseDelay)
            fun answer(status: TransferStatus, reason: RejectReason? = null) = bankAnswer(request, status, reason)
            val (code, response) = when {
                attempt <= busyAttempts -> {
                    exchange.responseHeaders.add(HttpHeaders.RETRY_AFTER, busyRetryAfterSeconds.toString())
                    HttpStatus.SERVICE_UNAVAILABLE.value() to """{"error":"${GatewayApi.BUSY_ERROR}"}"""
                }
                failFirstAttempt && attempt == FIRST_ATTEMPT -> HttpStatus.SERVICE_UNAVAILABLE.value() to """{"error":"unavailable"}"""
                refuseWith != null -> refuseWith to """{"error":"refused"}"""
                rawAnswer != null -> HttpStatus.OK.value() to rawAnswer(request)
                waited && rejectReason != null -> HttpStatus.OK.value() to answer(TransferStatus.REJECTED, rejectReason)
                waited -> HttpStatus.OK.value() to answer(TransferStatus.POSTED)
                else -> HttpStatus.ACCEPTED.value() to answer(TransferStatus.ACCEPTED)
            }
            if (HttpStatus.valueOf(code).is2xxSuccessful) record(request)
            respond(exchange, code, response)
        }
        server.createContext("/v1/runs/") { exchange ->
            val runId = UUID.fromString(exchange.requestURI.path.split("/")[3])
            val after = exchange.requestURI.query?.split("&")
                ?.firstOrNull { it.startsWith("${GatewayApi.AFTER_PARAM}=") }
                ?.substringAfter("=")?.let(UUID::fromString)
            val pageNumber = statementRequests.incrementAndGet()
            val failure = statementFailure(pageNumber)
            when {
                failure != null -> respond(exchange, failure, """{"error":"failed"}""")
                !periodClosed -> respond(exchange, HttpStatus.CONFLICT.value(), """{"error":"period not closed"}""")
                rawStatement != null -> respond(exchange, HttpStatus.OK.value(), rawStatement)
                else -> respond(exchange, HttpStatus.OK.value(), objectMapper.writeValueAsString(tamperStatement(pageNumber, statement(after))))
            }
        }
        server.start()
    }

    private fun record(request: SubmitTransferRequest) = synchronized(accepted) {
        accepted.putIfAbsent(
            request.transferId,
            StatementLine(
                request.transferId, request.fromAccount, request.toAccount, request.amountCents,
                if (rejectReason != null) TransferStatus.REJECTED else TransferStatus.POSTED,
                rejectReason,
            ),
        )
    }

    private fun statement(after: UUID?): Statement = synchronized(accepted) {
        val lines = accepted.values.toList()
        val from = after?.let { id -> lines.indexOfFirst { it.transferId == id } + 1 } ?: 0
        val page = lines.drop(from).take(statementPage)
        val next = if (from + page.size < lines.size) page.last().transferId else null
        Statement(lines.size.toLong(), page, next)
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        private val answerMapper = jacksonObjectMapper()

        /** Shaped as gateway/openapi.json's TransferResponse, timestamps included, though the generator reads fewer fields. */
        fun bankAnswer(request: SubmitTransferRequest, status: TransferStatus, reason: RejectReason? = null): String {
            val now = Instant.now().toString()
            return answerMapper.writeValueAsString(
                mapOf(
                    "transferId" to request.transferId, "runId" to request.runId, "status" to status, "rejectReason" to reason,
                    "acceptedAt" to now, "resultedAt" to now.takeIf { status.isFinal },
                ),
            )
        }
    }
}
