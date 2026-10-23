package com.ledgerlab.gateway.api

import com.ledgerlab.gateway.GatewayProperties
import com.ledgerlab.gateway.persistence.RunPeriodRepository
import com.ledgerlab.gateway.persistence.TransferRepository
import com.ledgerlab.gateway.service.TransferAdmission
import com.ledgerlab.gateway.service.TransferRequest
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the submission path before the gateway takes runs: a cold JIT answered the first seconds of a run after a
 * deploy with 503 busy. Every round is rolled back, so nothing reaches the transfers, the outbox or Kafka. The gateway
 * takes traffic after the short limit, so a deploy does not wait; control admits runs only once [WARM] reads 1.
 */
@Component
class TransferWarmUp(
    private val transfers: TransferRepository,
    private val periods: RunPeriodRepository,
    private val transactions: PlatformTransactionManager,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
    private val events: ApplicationEventPublisher,
    private val props: GatewayProperties,
    registry: MeterRegistry,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val warm = AtomicInteger(NOT_WARM).also { registry.gauge(WARM, it) }

    override fun run(args: ApplicationArguments) = warmUp()

    /** Takes traffic after the short limit in any case: a gateway that cannot warm up still serves, only slower at first. */
    fun warmUp() {
        warm.set(NOT_WARM)
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC)
        val started = System.nanoTime()
        // Not closed with use: closing waits for the round, and a round stuck on the database must not hold the start.
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val rounds = executor.submit<Int> { rounds(started + props.warmUp.maxDuration.toNanos()) }
        try {
            report(rounds.get(props.warmUp.duration.toNanos(), TimeUnit.NANOSECONDS), started)
            finish(executor)
        } catch (_: TimeoutException) {
            log.info("Warm-up takes traffic after {}; the rounds go on for up to {}", props.warmUp.duration, props.warmUp.maxDuration)
            executor.submit { awaitBehind(rounds, started, executor) }
        } catch (e: ExecutionException) {
            log.warn("Warm-up failed", e.cause)
            finish(executor)
        }
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC)
    }

    private fun awaitBehind(rounds: Future<Int>, started: Long, executor: ExecutorService) {
        try {
            // The rounds stop at the limit themselves; the margin lets the last one return, so a timeout means a stuck round.
            val left = started + (props.warmUp.maxDuration + RESULT_MARGIN).toNanos() - System.nanoTime()
            report(rounds.get(left, TimeUnit.NANOSECONDS), started)
        } catch (_: TimeoutException) {
            log.warn("Warm-up did not finish a round within its limit of {}", props.warmUp.maxDuration)
        } catch (e: ExecutionException) {
            log.warn("Warm-up failed", e.cause)
        } finally {
            finish(executor)
        }
    }

    private fun report(done: Int, started: Long) {
        if (done < props.warmUp.rounds) {
            log.warn("Warm-up reached its limit of {} after {} of {} rounds", props.warmUp.maxDuration, done, props.warmUp.rounds)
        } else {
            log.info("Warm-up: {} rounds in {} ms", done, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        }
    }

    /** Warm also when the rounds failed or got stuck: runs must not wait for a gateway that will never get warmer. */
    private fun finish(executor: ExecutorService) {
        warm.set(WARM_VALUE)
        executor.shutdownNow()
    }

    private fun rounds(deadline: Long): Int {
        // Instances of its own count into a registry nobody reads: the rounds are not transfers.
        val registry = SimpleMeterRegistry()
        val service = TransferService(transfers, periods, registry)
        val admission = TransferAdmission(props, registry)
        val rolledBack = TransactionTemplate(transactions)
        var done = 0
        while (done < props.warmUp.rounds && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
            round(service, admission, rolledBack)
            done++
        }
        return done
    }

    private fun round(service: TransferService, admission: TransferAdmission, rolledBack: TransactionTemplate) {
        val body = objectMapper.writeValueAsString(SubmitTransferRequest(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 1))
        val request = objectMapper.readValue(body, SubmitTransferRequest::class.java)
        check(validator.validate(request).isEmpty()) { "the warm-up request is invalid" }
        rolledBack.executeWithoutResult { status ->
            status.setRollbackOnly()
            val transfer = admission.admit {
                service.accept(TransferRequest(request.transferId!!, request.runId!!, request.fromAccount, request.toAccount, request.amountCents))
            }
            objectMapper.writeValueAsString(transfer.toResponse())
        }
    }

    companion object {
        /** 1 once the rounds are done or their limit passed; control reads it from Prometheus. */
        const val WARM = "gateway.warm_up.complete"
        private const val NOT_WARM = 0
        private const val WARM_VALUE = 1
        private val RESULT_MARGIN: Duration = Duration.ofMillis(500)
    }
}
