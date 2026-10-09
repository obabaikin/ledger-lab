package com.ledgerlab.generator

import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Minimal stand-in for the gateway's POST /transfers, with an optional first-attempt failure, a refusal and a slow answer. */
class FakeGateway(
    private val failFirstAttempt: Boolean = false,
    private val refuseWith: Int? = null,
    private val responseDelay: Duration = Duration.ZERO,
) : AutoCloseable {
    private val objectMapper = ObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    val attemptsById = ConcurrentHashMap<String, AtomicInteger>()
    val requests = AtomicInteger()

    val url: String get() = "http://localhost:${server.address.port}"

    init {
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext(GatewayApi.TRANSFERS_PATH) { exchange ->
            requests.incrementAndGet()
            val body = objectMapper.readValue(exchange.requestBody.readAllBytes(), Map::class.java)
            val id = body["transferId"] as String
            val attempt = attemptsById.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            val waited = exchange.requestURI.query?.contains("wait=") == true
            Thread.sleep(responseDelay)
            val (code, response) = when {
                failFirstAttempt && attempt == 1 -> 503 to """{"error":"unavailable"}"""
                refuseWith != null -> refuseWith to """{"error":"refused"}"""
                waited -> 200 to """{"transferId":"$id","status":"POSTED"}"""
                else -> 202 to """{"transferId":"$id","status":"ACCEPTED"}"""
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
