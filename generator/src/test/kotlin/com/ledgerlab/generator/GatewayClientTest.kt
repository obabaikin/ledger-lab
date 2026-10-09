package com.ledgerlab.generator

import com.ledgerlab.common.TransferCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.UUID

class GatewayClientTest {

    private fun client(url: String, maxAttempts: Int, retryBackoff: Duration = Duration.ofMillis(50)) =
        GatewayClient(
            GeneratorProperties(url, Duration.ofSeconds(2), Duration.ofSeconds(10), maxAttempts, retryBackoff, 500, Duration.ofHours(1), Duration.ofMinutes(5)),
            ObjectMapper(),
        )

    private fun command() = TransferCommand(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100, Instant.now())

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `a gateway that cannot be reached on any attempt is a failure`(): Unit = runBlocking {
        val result = client("http://localhost:${closedPort()}", maxAttempts = 2).submit(command(), wait = null)

        assertThat(result.outcome).isEqualTo(SubmissionOutcome.FAILED)
        assertThat(result.attempts).isEqualTo(2)
    }

    @Test
    fun `once an attempt may have reached the gateway, a later connection failure keeps it unknown`(): Unit = runBlocking {
        val gateway = FakeGateway(failFirstAttempt = true)
        val client = client(gateway.url, maxAttempts = 2, retryBackoff = Duration.ofSeconds(1))

        val pending = async(Dispatchers.IO) { client.submit(command(), wait = null) }
        await().atMost(Duration.ofSeconds(5)).until { gateway.requests.get() == 1 }
        gateway.close() // the retry finds no gateway

        val result = pending.await()
        assertThat(result.outcome).isEqualTo(SubmissionOutcome.UNKNOWN)
        assertThat(result.attempts).isEqualTo(2)
    }
}
