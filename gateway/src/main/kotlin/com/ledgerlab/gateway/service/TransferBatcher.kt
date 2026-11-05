package com.ledgerlab.gateway.service

import com.ledgerlab.gateway.GatewayProperties
import com.ledgerlab.gateway.domain.Transfer
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GatewayStoppingException : RuntimeException("The gateway is stopping")

/** The submission may still be written after this: to the client it is unknown, as any other 5xx. */
class AcceptTimeoutException(timeout: Duration) : RuntimeException("No answer from the writers within $timeout")

/**
 * Submissions wait in one queue; each writer takes what gathered while its last batch committed, so a lone request
 * goes at once and a busy gateway commits many per transaction. The admission limit bounds the queue.
 */
@Component
class TransferBatcher(
    private val writeBatch: (List<TransferRequest>) -> List<Result<Transfer>>,
    props: GatewayProperties,
    registry: MeterRegistry,
) {
    @Autowired
    constructor(service: TransferService, props: GatewayProperties, registry: MeterRegistry) :
        this(service::acceptAll, props, registry)

    private val log = LoggerFactory.getLogger(javaClass)
    private val queue = LinkedBlockingQueue<Pending>()
    private val maxBatch = props.accept.batchSize
    private val answerTimeout = props.accept.answerTimeout
    private val batchSizes = DistributionSummary.builder("gateway.accept.batch.size")
        .description("submissions committed in one transaction")
        .publishPercentiles(*BATCH_PERCENTILES)
        .register(registry)
    private val writers = List(props.accept.writers) { index ->
        Thread.ofPlatform().name("$WRITER_THREAD$index").daemon().unstarted(::write)
    }

    @Volatile private var stopping = false

    private class Pending(val request: TransferRequest) {
        val result = CompletableFuture<Transfer>()
    }

    @PostConstruct
    fun start() = writers.forEach(Thread::start)

    @PreDestroy
    fun stop() {
        stopping = true
        writers.forEach(Thread::interrupt)
        writers.forEach { it.join(STOP_WAIT.toMillis()) }
        generateSequence { queue.poll() }.forEach { it.result.completeExceptionally(GatewayStoppingException()) }
    }

    /** Returns after the commit, with the outcome a request of its own would have had. */
    fun accept(request: TransferRequest): Transfer {
        if (stopping) throw GatewayStoppingException()
        val pending = Pending(request)
        queue.put(pending)
        try {
            return pending.result.get(answerTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (_: TimeoutException) {
            throw AcceptTimeoutException(answerTimeout)
        }
    }

    private fun write() {
        val batch = ArrayList<Pending>(maxBatch)
        while (true) {
            try {
                batch.add(queue.take())
            } catch (_: InterruptedException) {
                return
            }
            queue.drainTo(batch, maxBatch - 1)
            batchSizes.record(batch.size.toDouble())
            try {
                write(batch)
            } catch (e: Throwable) {
                // A writer that dies leaves its requests and every later one of its queue waiting: answer and go on.
                batch.forEach { it.result.completeExceptionally(e) }
                if (e is InterruptedException) return
            } finally {
                batch.clear()
            }
        }
    }

    /**
     * A batch whose data the database refused is written again one by one, so that request fails alone. Any other
     * failure (no connection, the database down) fails the whole batch: one by one would only multiply it.
     */
    private fun write(batch: List<Pending>) {
        val outcomes = try {
            writeBatch(batch.map { it.request })
        } catch (e: DataIntegrityViolationException) {
            if (batch.size == 1) {
                batch.single().result.completeExceptionally(e)
            } else {
                log.warn("The database refused the data of a batch of {} transfers; writing them one by one", batch.size, e)
                batch.forEach { write(listOf(it)) }
            }
            return
        }
        batch.zip(outcomes).forEach { (pending, outcome) ->
            outcome.fold(pending.result::complete, pending.result::completeExceptionally)
        }
    }

    companion object {
        const val WRITER_THREAD = "accept-writer-"
        private val STOP_WAIT: Duration = Duration.ofSeconds(5)
        private val BATCH_PERCENTILES = doubleArrayOf(0.5, 0.99)
    }
}
