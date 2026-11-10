package com.ledgerlab.gateway

import com.ledgerlab.gateway.domain.Transfer
import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.gateway.service.AcceptTimeoutException
import com.ledgerlab.gateway.service.GatewayStoppingException
import com.ledgerlab.gateway.service.TransferBatcher
import com.ledgerlab.gateway.service.TransferRequest
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class TransferBatcherTest {

    private val service = mock(TransferService::class.java)
    private val calls = CopyOnWriteArrayList<Int>()
    private val firstHeld = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    private lateinit var batcher: TransferBatcher

    @AfterEach
    fun tearDown() {
        releaseFirst.countDown()
        batcher.stop()
        pool.shutdownNow()
    }

    private fun batcher(answerTimeout: Duration = Duration.ofSeconds(5)): TransferBatcher {
        val props = TransferAdmissionTest.props.copy(accept = GatewayProperties.Accept(10, 1, answerTimeout))
        return TransferBatcher(service, props, SimpleMeterRegistry()).also { batcher = it; it.start() }
    }

    private fun request() = TransferRequest(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100)

    private fun TransferRequest.row() =
        Transfer(transferId, runId, fromAccount, toAccount, amountCents, TransferStatus.ACCEPTED, null, Instant.now(), null)

    /** The first call holds the only writer, so the next requests gather into one batch behind it. */
    private fun answering(batch: (List<TransferRequest>) -> List<Result<Transfer>>) {
        doAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val requests = call.getArgument<List<TransferRequest>>(0)
            calls += requests.size
            if (calls.size == 1) {
                firstHeld.countDown()
                releaseFirst.await()
            }
            batch(requests)
        }.`when`(service).acceptAll(anyList())
    }

    private fun submitBehindFirst(count: Int): List<Future<Transfer>> {
        val first = pool.submit<Transfer> { batcher.accept(request()) }
        firstHeld.await(5, TimeUnit.SECONDS)
        val rest = List(count) { pool.submit<Transfer> { batcher.accept(request()) } }
        Thread.sleep(GATHER_MILLIS)
        releaseFirst.countDown()
        first.get(5, TimeUnit.SECONDS)
        return rest
    }

    @Test
    fun `a batch that gets no connection fails as a whole and is not split into single writes`() {
        answering { requests ->
            if (requests.size > 1) throw CannotCreateTransactionException("no connection")
            requests.map { Result.success(it.row()) }
        }
        batcher()

        val gathered = submitBehindFirst(3)

        gathered.forEach { assertThatThrownBy { it.get(5, TimeUnit.SECONDS) }.hasCauseInstanceOf(CannotCreateTransactionException::class.java) }
        assertThat(calls).containsExactly(1, 3)
    }

    @Test
    fun `a batch whose data the database refuses is written again one by one`() {
        answering { requests ->
            if (requests.size > 1) throw DataIntegrityViolationException("bad row")
            requests.map { Result.success(it.row()) }
        }
        batcher()

        val gathered = submitBehindFirst(3)

        gathered.forEach { assertThat(it.get(5, TimeUnit.SECONDS)).isNotNull() }
        assertThat(calls).containsExactly(1, 3, 1, 1, 1)
    }

    @Test
    fun `a writer outlives an error of its batch and answers the next one`() {
        answering { requests ->
            if (requests.size > 1) throw OutOfMemoryError("simulated")
            requests.map { Result.success(it.row()) }
        }
        batcher()

        val gathered = submitBehindFirst(2)
        gathered.forEach { assertThatThrownBy { it.get(5, TimeUnit.SECONDS) }.hasCauseInstanceOf(OutOfMemoryError::class.java) }

        assertThat(batcher.accept(request())).isNotNull()
    }

    @Test
    fun `a request the writers do not answer in time gets a timeout, not an endless wait`() {
        answering { requests -> requests.map { Result.success(it.row()) } }
        batcher(answerTimeout = Duration.ofMillis(200))

        pool.submit<Transfer> { batcher.accept(request()) }
        firstHeld.await(5, TimeUnit.SECONDS)

        assertThatThrownBy { batcher.accept(request()) }.isInstanceOf(AcceptTimeoutException::class.java)
    }

    @Test
    fun `requests still in the queue when the gateway stops are answered that it is stopping`() {
        answering { requests -> requests.map { Result.success(it.row()) } }
        batcher()
        pool.submit<Transfer> { batcher.accept(request()) }
        firstHeld.await(5, TimeUnit.SECONDS)
        val queued = List(3) { pool.submit<Transfer> { batcher.accept(request()) } }
        Thread.sleep(GATHER_MILLIS)

        batcher.stop()

        queued.forEach {
            assertThatThrownBy { it.get(1, TimeUnit.SECONDS) }.hasCauseInstanceOf(GatewayStoppingException::class.java)
        }
    }

    @Test
    fun `a request arriving after the stop is refused at once`() {
        answering { requests -> requests.map { Result.success(it.row()) } }
        batcher().stop()

        assertThatThrownBy { batcher.accept(request()) }.isInstanceOf(GatewayStoppingException::class.java)
    }

    private companion object {
        const val GATHER_MILLIS = 200L
    }
}
