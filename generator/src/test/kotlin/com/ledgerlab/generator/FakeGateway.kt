package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferResponse
import com.ledgerlab.generator.bank.TransferStatus
import com.sun.net.httpserver.HttpServer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal stand-in for the gateway's POST /transfers, with an optional first-attempt failure, a refusal,
 * a slow answer, a slow answer to repeats of an id, a ledger rejection for waited requests, and a 200
 * with a body as a newer gateway might send it.
 */
class FakeGateway(
    private val failFirstAttempt: Boolean = false,
    private val refuseWith: Int? = null,
    private val responseDelay: Duration = Duration.ZERO,
    private val rejectReason: RejectReason? = null,
    private val repeatDelay: Duration = Duration.ZERO,
    private val rawAnswer: ((SubmitTransferRequest) -> String)? = null,
) : AutoCloseable {
    private val objectMapper = jacksonObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    val attemptsById = ConcurrentHashMap<String, AtomicInteger>()
    val requests = AtomicInteger()

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
            val bytes = response.toByteArray()
            exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
