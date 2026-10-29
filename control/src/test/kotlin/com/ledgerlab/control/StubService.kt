package com.ledgerlab.control

import com.sun.net.httpserver.HttpServer
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class StubService : AutoCloseable {
    @Volatile var status: Int = 200
    @Volatile var delay: Duration = Duration.ZERO

    /** [headers] by lowercased name. */
    data class Call(val method: String, val path: String, val body: String, val headers: Map<String, String> = emptyMap())

    private data class Answer(val status: Int, val body: String?, val delay: Duration)

    private val answers = ConcurrentHashMap<String, Answer>()
    val calls = CopyOnWriteArrayList<Call>()

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.joinToString(",") }
            calls += Call(method, path, exchange.requestBody.readAllBytes().decodeToString(), headers)
            val answer = exchange.requestURI.rawQuery?.let { answers["$method $path?${URLDecoder.decode(it, StandardCharsets.UTF_8)}"] } ?: answers["$method $path"]
            Thread.sleep(answer?.delay ?: delay)
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

    fun answer(method: String, path: String, status: Int, body: Any? = null, delay: Duration = Duration.ZERO) {
        answers["$method $path"] = Answer(status, body?.let { it as? String ?: mapper.writeValueAsString(it) }, delay)
    }

    /** An answer to one query string only, e.g. one PromQL query; [query] is the whole query string, form-decoded as Prometheus reads it. */
    fun answerQuery(method: String, path: String, query: String, status: Int, body: Any?) = answer(method, "$path?$query", status, body)

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
