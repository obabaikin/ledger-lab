package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.HttpStatus
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class GatewayClientTest {

    private val registry = SimpleMeterRegistry()

    private fun client(url: String, maxAttempts: Int, retryBackoff: Duration = Duration.ofMillis(50)) =
        GatewayClient(testProperties(url, maxAttempts, retryBackoff), jacksonObjectMapper(), registry)

    private fun unreadable() = registry.counter(GatewayClient.UNREADABLE_METRIC).count()

    private fun transfer() = PlannedTransfer(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100)

    private fun JournalEntry.sends() = SendRecorder { attempt, startedNanos, endedNanos, result, httpStatus ->
        record(if (attempt == FIRST_ATTEMPT) SendKind.FIRST else SendKind.RETRY, startedNanos, endedNanos, result, httpStatus)
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun httpClientPlatformWorkers(): Int =
        Thread.getAllStackTraces().keys.count { !it.isVirtual && it.name.startsWith("HttpClient-") && it.name.contains("-Worker-") }

    @Test
    fun `answers to many open submissions arriving at once take no platform thread each`(): Unit = runBlocking {
        FakeGateway().use { gateway ->
            val held = CountDownLatch(1)
            gateway.answersHeld = held
            val client = client(gateway.url, maxAttempts = 1)
            val pending = List(OPEN_SUBMISSIONS) { async(Dispatchers.IO) { client.submit(transfer(), wait = null) } }
            await().atMost(Duration.ofSeconds(30)).until { gateway.requests.get() == OPEN_SUBMISSIONS }

            // Other clients in this JVM keep idle workers of the same name for a minute; only new ones count.
            val before = httpClientPlatformWorkers()
            val peak = AtomicInteger(before)
            val watcher = thread {
                while (!Thread.currentThread().isInterrupted) {
                    peak.accumulateAndGet(httpClientPlatformWorkers(), ::maxOf)
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                }
            }
            held.countDown()
            val results = pending.awaitAll()
            watcher.interrupt()
            watcher.join()

            assertThat(results).allSatisfy { assertThat(it.outcome).isEqualTo(SubmissionOutcome.ACKNOWLEDGED) }
            assertThat(peak.get() - before).isZero()
        }
    }

    @Test
    fun `a gateway that cannot be reached on any attempt is a failure`(): Unit = runBlocking {
        val transfer = transfer()
        val entry = JournalEntry(1, transfer, System.nanoTime())

        val result = client("http://localhost:${closedPort()}", maxAttempts = 2).submit(transfer, wait = null, sends = entry.sends())

        assertThat(result.outcome).isEqualTo(SubmissionOutcome.FAILED)
        assertThat(result.attempts).isEqualTo(2)
        val view = entry.view()
        assertThat(view.first!!.result).isEqualTo(SendResult.NOT_CONNECTED)
        assertThat(view.first.httpStatus).isNull()
        assertThat(view.resends.map { it.number to it.result }).containsExactly(2 to SendResult.NOT_CONNECTED)
    }

    @Test
    fun `once an attempt may have reached the gateway, a later connection failure keeps it unknown`(): Unit = runBlocking {
        val gateway = FakeGateway(failFirstAttempt = true)
        val client = client(gateway.url, maxAttempts = 2, retryBackoff = Duration.ofSeconds(1))

        val transfer = transfer()
        val entry = JournalEntry(1, transfer, System.nanoTime())
        val pending = async(Dispatchers.IO) { client.submit(transfer, wait = null, sends = entry.sends()) }
        // Waiting for the request alone is not enough: closing before the 503 is written makes the first send NO_RESPONSE.
        await().atMost(Duration.ofSeconds(5)).until { entry.view().first != null }
        gateway.close() // the retry finds no gateway

        val result = pending.await()
        assertThat(result.outcome).isEqualTo(SubmissionOutcome.UNKNOWN)
        assertThat(result.attempts).isEqualTo(2)
        assertThat(entry.view().first!!.httpStatus).isEqualTo(503)
        assertThat(entry.view().resends.single().result).isEqualTo(SendResult.NOT_CONNECTED)
    }

    @Test
    fun `a 2xx with a status this client does not know is acknowledged without a status`(): Unit = runBlocking {
        FakeGateway(rawAnswer = { """{"transferId":"${it.transferId}","runId":"${it.runId}","status":"SETTLED","acceptedAt":"${Instant.now()}"}""" })
            .use { gateway ->
                val result = client(gateway.url, maxAttempts = 1).submit(transfer(), wait = null)

                assertThat(result.outcome).isEqualTo(SubmissionOutcome.ACKNOWLEDGED)
                assertThat(result.status).isNull()
                assertThat(unreadable()).isEqualTo(1.0)
            }
    }

    @Test
    fun `a reject reason this client does not know keeps the status it knows`(): Unit = runBlocking {
        val answer = { r: SubmitTransferRequest ->
            """{"transferId":"${r.transferId}","runId":"${r.runId}","status":"${TransferStatus.REJECTED}","rejectReason":"CARD_EXPIRED","acceptedAt":"${Instant.now()}"}"""
        }
        FakeGateway(rawAnswer = answer).use { gateway ->
            val result = client(gateway.url, maxAttempts = 1).submit(transfer(), wait = null)

            assertThat(result.outcome).isEqualTo(SubmissionOutcome.ACKNOWLEDGED)
            assertThat(result.status).isEqualTo(TransferStatus.REJECTED)
            assertThat(result.rejectReason).isNull()
            assertThat(unreadable()).isEqualTo(1.0)
        }
    }

    @Test
    fun `an answer it can read and a refusal with an error body are not counted as unreadable`(): Unit = runBlocking {
        FakeGateway().use { gateway -> client(gateway.url, maxAttempts = 1).submit(transfer(), wait = null) }
        FakeGateway(refuseWith = HttpStatus.CONFLICT.value()).use { gateway ->
            val result = client(gateway.url, maxAttempts = 1).submit(transfer(), wait = null)

            assertThat(result.outcome).isEqualTo(SubmissionOutcome.FAILED)
        }

        assertThat(unreadable()).isZero()
    }

    private fun statementPage(status: String, rejectReason: String?) =
        """{"entryCount":1,"items":[{"transferId":"${UUID.randomUUID()}","fromAccount":1,"toAccount":2,"amountCents":100,""" +
            """"status":"$status","rejectReason":${rejectReason?.let { "\"$it\"" }}}],"next":null}"""

    @Test
    fun `a statement line with a status this client does not know is read without it and counted`() {
        FakeGateway(rawStatement = statementPage("SETTLED", null)).use { gateway ->
            val line = client(gateway.url, maxAttempts = 1).statement(UUID.randomUUID(), after = null).items.single()

            assertThat(line.status).isNull()
            assertThat(unreadable()).isEqualTo(1.0)
        }
    }

    @Test
    fun `a statement line with a reject reason this client does not know keeps its status and is counted`() {
        FakeGateway(rawStatement = statementPage(TransferStatus.REJECTED.name, "CARD_EXPIRED")).use { gateway ->
            val line = client(gateway.url, maxAttempts = 1).statement(UUID.randomUUID(), after = null).items.single()

            assertThat(line.status).isEqualTo(TransferStatus.REJECTED)
            assertThat(line.rejectReason).isNull()
            assertThat(unreadable()).isEqualTo(1.0)
        }
    }

    @Test
    fun `a statement page without an entry count, as from a gateway older than this client, is a bad answer`() {
        FakeGateway(rawStatement = """{"items":[],"next":null}""").use { gateway ->
            assertThatThrownBy { client(gateway.url, maxAttempts = 1).statement(UUID.randomUUID(), after = null) }
                .isInstanceOf(BadGatewayAnswerException::class.java)
        }
    }

    @Test
    fun `a statement page it can read in full is not counted as unreadable`(): Unit = runBlocking {
        FakeGateway(rejectReason = RejectReason.BLOCKED_ACCOUNT).use { gateway ->
            val client = client(gateway.url, maxAttempts = 1)
            val transfer = transfer()
            client.submit(transfer, wait = Duration.ofSeconds(1))

            assertThat(client.statement(transfer.runId, after = null).items.single().rejectReason).isEqualTo(RejectReason.BLOCKED_ACCOUNT)
            assertThat(unreadable()).isZero()
        }
    }
}

// As many as the server's slow answers kept open at 1300 TPS, where the generator's container ran out of memory.
private const val OPEN_SUBMISSIONS = 500
