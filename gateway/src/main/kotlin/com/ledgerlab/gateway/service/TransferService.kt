package com.ledgerlab.gateway.service

import com.ledgerlab.common.Observability.LATENCY_PERCENTILES
import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.gateway.domain.RunSummary
import com.ledgerlab.gateway.domain.Transfer
import com.ledgerlab.gateway.persistence.RunPeriodRepository
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

/**
 * A result as the ledger sent it. The reason stays text: a ledger one version ahead may send one this gateway
 * does not know yet, and it is kept as received rather than lost (ADR 0011).
 */
data class LedgerResult(val runId: UUID, val transferId: UUID, val outcome: TransferOutcome, val rejectReason: String?)

class IdempotencyConflictException(transferId: UUID) :
    RuntimeException("Transfer $transferId was already submitted with a different payload")

class PeriodClosedException(runId: UUID) : RuntimeException("The period of run $runId is closed")

@Service
class TransferService(
    private val transfers: TransferRepository,
    private val periods: RunPeriodRepository,
    registry: MeterRegistry,
) {

    private val accepted = Counter.builder("gateway.transfers").tag("outcome", "accepted").register(registry)
    private val repeated = Counter.builder("gateway.transfers").tag("outcome", "repeated").register(registry)
    private val latency = Timer.builder("gateway.transfer.latency")
        .description("accepted at the edge to result known")
        .publishPercentiles(*LATENCY_PERCENTILES)
        .register(registry)

    /**
     * Accepting a transfer is one committed row; the response goes out before Kafka is involved.
     * A repeated submission with the same id returns the current state instead of a second transfer,
     * also after the run's period is closed; a new id is then refused (ADR 0009).
     */
    @Transactional
    fun accept(request: TransferRequest): Transfer {
        periods.lockShared(request.runId)
        // A separate statement after the lock: its snapshot must see a close that committed while we waited.
        if (periods.find(request.runId) != null) {
            val existing = transfers.find(request.transferId) ?: throw PeriodClosedException(request.runId)
            return repeat(existing, request)
        }
        transfers.insertIfAbsent(
            request.transferId, request.runId, request.fromAccount, request.toAccount, request.amountCents,
        )?.let {
            accepted.increment()
            return it
        }
        return repeat(checkNotNull(transfers.find(request.transferId)), request)
    }

    private fun repeat(existing: Transfer, request: TransferRequest): Transfer {
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
    fun recordResult(result: LedgerResult): Transfer? {
        transfers.applyResult(result.transferId, result.outcome, result.rejectReason)?.let { applied ->
            latency.record(Duration.between(applied.acceptedAt, checkNotNull(applied.resultedAt)))
            return applied
        }
        return transfers.find(result.transferId)
    }

    fun find(transferId: UUID): Transfer? = transfers.find(transferId)

    @Transactional(readOnly = true)
    fun summarize(runId: UUID): RunSummary = transfers.summarize(runId)

    /** The period goes with the run; the answer reflects the transfers only. */
    @Transactional
    fun deleteRun(runId: UUID): Boolean {
        periods.delete(runId)
        return transfers.deleteRun(runId) > 0
    }

    private fun Transfer.matches(request: TransferRequest) =
        runId == request.runId && fromAccount == request.fromAccount &&
            toAccount == request.toAccount && amountCents == request.amountCents
}
