package com.ledgerlab.gateway.service

import com.ledgerlab.common.Observability.LATENCY_PERCENTILES
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.gateway.domain.RunSummary
import com.ledgerlab.gateway.domain.Transfer
import com.ledgerlab.gateway.persistence.TransferRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

data class TransferRequest(
    val transferId: UUID,
    val runId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
)

class IdempotencyConflictException(transferId: UUID) :
    RuntimeException("Transfer $transferId was already submitted with a different payload")

@Service
class TransferService(private val transfers: TransferRepository, registry: MeterRegistry) {

    private val accepted = Counter.builder("gateway.transfers").tag("outcome", "accepted").register(registry)
    private val repeated = Counter.builder("gateway.transfers").tag("outcome", "repeated").register(registry)
    private val latency = Timer.builder("gateway.transfer.latency")
        .description("accepted at the edge to result known")
        .publishPercentiles(*LATENCY_PERCENTILES)
        .register(registry)

    /**
     * Accepting a transfer is one committed row; the response goes out before Kafka is involved.
     * A repeated submission with the same id returns the current state instead of a second transfer.
     */
    @Transactional
    fun accept(request: TransferRequest): Transfer {
        transfers.insertIfAbsent(
            request.transferId, request.runId, request.fromAccount, request.toAccount, request.amountCents,
        )?.let {
            accepted.increment()
            return it
        }
        val existing = checkNotNull(transfers.find(request.transferId))
        if (!existing.matches(request)) throw IdempotencyConflictException(request.transferId)
        repeated.increment()
        return existing
    }

    /**
     * A waiting client is answered from this committed row, never from the event alone, so a
     * final status it has seen is never followed by an older one. Latency uses database
     * timestamps only, because JVM and database clocks may drift apart.
     */
    @Transactional
    fun recordResult(event: TransferResultEvent): Transfer? {
        transfers.applyResult(event.transferId, event.outcome, event.rejectReason)?.let { applied ->
            latency.record(Duration.between(applied.acceptedAt, checkNotNull(applied.resultedAt)))
            return applied
        }
        return transfers.find(event.transferId)
    }

    fun find(transferId: UUID): Transfer? = transfers.find(transferId)

    @Transactional(readOnly = true)
    fun summarize(runId: UUID): RunSummary = transfers.summarize(runId)

    @Transactional
    fun deleteRun(runId: UUID): Boolean = transfers.deleteRun(runId) > 0

    private fun Transfer.matches(request: TransferRequest) =
        runId == request.runId && fromAccount == request.fromAccount &&
            toAccount == request.toAccount && amountCents == request.amountCents
}
