package com.ledgerlab.control

import com.sun.net.httpserver.HttpServer
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class StubService : AutoCloseable {
    @Volatile var status: Int = 200
    @Volatile var delay: Duration = Duration.ZERO

    data class Call(val method: String, val path: String, val body: String)

    private data class Answer(val status: Int, val body: String?)

    private val answers = ConcurrentHashMap<String, Answer>()
    val calls = CopyOnWriteArrayList<Call>()

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            Thread.sleep(delay)
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            calls += Call(method, path, exchange.requestBody.readAllBytes().decodeToString())
            val answer = answers["$method $path"]
            val body = answer?.body?.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(answer?.status ?: status, body?.size?.toLong()?.takeIf { it > 0 } ?: -1)
            body?.let { exchange.responseBody.write(it) }
            exchange.close()
        }
        executor = Executors.newVirtualThreadPerTaskExecutor()
        start()
    }

    val baseUrl: String = "http://localhost:${server.address.port}"

    fun answer(method: String, path: String, status: Int, body: Any? = null) {
        answers["$method $path"] = Answer(status, body?.let { mapper.writeValueAsString(it) })
    }

    fun callsTo(method: String, path: String): List<Call> = calls.filter { it.method == method && it.path == path }

    fun reset() {
        status = 200
        delay = Duration.ZERO
        answers.clear()
        calls.clear()
    }

    override fun close() = server.stop(0)

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}
