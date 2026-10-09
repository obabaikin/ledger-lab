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
        GatewayClient(testProperties(url, maxAttempts, retryBackoff), ObjectMapper())

    private fun command() = TransferCommand(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100, Instant.now())

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `a gateway that cannot be reached on any attempt is a failure`(): Unit = runBlocking {
        val command = command()
        val entry = JournalEntry(1, command, System.nanoTime())

        val result = client("http://localhost:${closedPort()}", maxAttempts = 2).submit(command, wait = null, sends = entry)

        assertThat(result.outcome).isEqualTo(SubmissionOutcome.FAILED)
        assertThat(result.attempts).isEqualTo(2)
        val view = entry.view()
        assertThat(view.first!!.result).isEqualTo(SendResult.NOT_CONNECTED)
        assertThat(view.first!!.httpStatus).isNull()
        assertThat(view.resends.map { it.number to it.result }).containsExactly(2 to SendResult.NOT_CONNECTED)
    }

    @Test
    fun `once an attempt may have reached the gateway, a later connection failure keeps it unknown`(): Unit = runBlocking {
        val gateway = FakeGateway(failFirstAttempt = true)
        val client = client(gateway.url, maxAttempts = 2, retryBackoff = Duration.ofSeconds(1))

        val command = command()
        val entry = JournalEntry(1, command, System.nanoTime())
        val pending = async(Dispatchers.IO) { client.submit(command, wait = null, sends = entry) }
        // Waiting for the request alone is not enough: closing before the 503 is written makes the first send NO_RESPONSE.
        await().atMost(Duration.ofSeconds(5)).until { entry.view().first != null }
        gateway.close() // the retry finds no gateway

        val result = pending.await()
        assertThat(result.outcome).isEqualTo(SubmissionOutcome.UNKNOWN)
        assertThat(result.attempts).isEqualTo(2)
        assertThat(entry.view().first!!.httpStatus).isEqualTo(503)
        assertThat(entry.view().resends.single().result).isEqualTo(SendResult.NOT_CONNECTED)
    }
}
