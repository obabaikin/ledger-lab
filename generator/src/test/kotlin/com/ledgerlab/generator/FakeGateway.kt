package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.Statement
import com.ledgerlab.generator.bank.StatementLine
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferResponse
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
) : AutoCloseable {
    private val objectMapper = jacksonObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    val attemptsById = ConcurrentHashMap<String, AtomicInteger>()
    val requests = AtomicInteger()
    private val accepted = LinkedHashMap<UUID, StatementLine>()
    private val statementRequests = AtomicInteger()

    val url: String get() = "http://localhost:${server.address.port}"

    init {
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext(GatewayApi.TRANSFERS_PATH) { exchange ->
            requests.incrementAndGet()
            val request = objectMapper.readValue<SubmitTransferRequest>(exchange.requestBody.readAllBytes())
            val id = request.transferId.toString()
            val attempt = attemptsById.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            val waited = exchange.requestURI.query?.contains("${GatewayApi.WAIT_PARAM}=") == true
            Thread.sleep(if (attempt > FIRST_ATTEMPT) repeatDelay else responseDelay)
            fun answer(status: TransferStatus, reason: RejectReason? = null) =
                objectMapper.writeValueAsString(TransferResponse(request.transferId!!, request.runId!!, status, reason, Instant.now(), null))
            val (code, response) = when {
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
            val failure = statementFailure(statementRequests.incrementAndGet())
            when {
                failure != null -> respond(exchange, failure, """{"error":"failed"}""")
                !periodClosed -> respond(exchange, HttpStatus.CONFLICT.value(), """{"error":"period not closed"}""")
                rawStatement != null -> respond(exchange, HttpStatus.OK.value(), rawStatement)
                else -> respond(exchange, HttpStatus.OK.value(), objectMapper.writeValueAsString(statement(runId, after)))
            }
        }
        server.start()
    }

    private fun record(request: SubmitTransferRequest) = synchronized(accepted) {
        accepted.putIfAbsent(
            request.transferId!!,
            StatementLine(
                request.transferId!!, request.fromAccount, request.toAccount, request.amountCents,
                if (rejectReason != null) TransferStatus.REJECTED else TransferStatus.POSTED,
                rejectReason,
            ),
        )
    }

    private fun statement(runId: UUID, after: UUID?): Statement = synchronized(accepted) {
        val lines = accepted.values.toList()
        val from = after?.let { id -> lines.indexOfFirst { it.transferId == id } + 1 } ?: 0
        val page = lines.drop(from).take(statementPage)
        val next = if (from + page.size < lines.size) page.last().transferId else null
        Statement(page, next)
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
