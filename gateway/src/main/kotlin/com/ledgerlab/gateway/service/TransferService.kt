package com.ledgerlab.gateway.service

import com.ledgerlab.common.Observability
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
        .publishPercentiles(*Observability.latencyPercentiles())
        .register(registry)

    /**
     * One transaction and one insert for many requests; each gets, in the same order, the outcome it would have had
     * alone: its row, or [PeriodClosedException] / [IdempotencyConflictException]. The responses go out after the
     * commit, before Kafka is involved.
     */
    @Transactional
    fun acceptAll(requests: List<TransferRequest>): List<Result<Transfer>> {
        // One order for every batch: a close waiting between two shared locks cannot close a cycle.
        requests.map { it.runId }.distinct().sortedBy { it.toString() }.forEach(periods::lockShared)
        // A separate statement after the locks: its snapshot must see a close that committed while we waited.
        val closed = periods.closedAmong(requests.map { it.runId }.toSet())
        val inserting = requests.filter { it.runId !in closed }.distinctBy { it.transferId }.associateBy { it.transferId }
        val inserted = transfers.insertAllIfAbsent(inserting.values.toList()).associateBy { it.transferId }
        accepted.increment(inserted.size.toDouble())
        val existing = transfers.findAll(requests.map { it.transferId }.filterNot(inserted::containsKey).toSet())
            .associateBy { it.transferId }
        val answeredAsInserted = HashSet<UUID>()
        return requests.map { request ->
            runCatching {
                val row = inserted[request.transferId]
                when {
                    row != null && inserting[request.transferId] === request && answeredAsInserted.add(request.transferId) -> row
                    row != null -> repeat(row, request)
                    else -> existing[request.transferId]?.let { repeat(it, request) }
                        ?: if (request.runId in closed) throw PeriodClosedException(request.runId)
                        else error("Transfer ${request.transferId} was neither inserted nor found")
                }
            }
        }
    }

    private fun repeat(existing: Transfer, request: TransferRequest): Transfer {
        if (!existing.matches(request)) throw IdempotencyConflictException(request.transferId)
        repeated.increment()
        return existing
    }

    /**
     * The only writer of results, one transaction per poll; a waiting client reads the committed row, so a final
     * status it has seen is never followed by an older one. Latency uses database timestamps only, because JVM and
     * database clocks may drift apart.
     */
    @Transactional
    fun recordResults(results: List<LedgerResult>) {
        transfers.applyResults(results).forEach { applied ->
            latency.record(Duration.between(applied.acceptedAt, checkNotNull(applied.resultedAt)))
        }
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
