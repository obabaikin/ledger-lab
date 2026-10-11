package com.ledgerlab.control

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration

class StubLedger : AutoCloseable {
    @Volatile var status: Int = 200
    @Volatile var delay: Duration = Duration.ZERO

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            Thread.sleep(delay)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        start()
    }

    val baseUrl: String = "http://localhost:${server.address.port}"

    fun reset() {
        status = 200
        delay = Duration.ZERO
    }

    override fun close() = server.stop(0)
}
