package com.ledgerlab.gateway.service

import com.ledgerlab.gateway.GatewayProperties
import com.ledgerlab.gateway.domain.RunPeriod
import com.ledgerlab.gateway.domain.Statement
import com.ledgerlab.gateway.persistence.RunPeriodRepository
import com.ledgerlab.gateway.persistence.TransferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class PeriodNotClosedException(runId: UUID) : RuntimeException("The period of run $runId is not closed")

@Service
class PeriodService(
    private val periods: RunPeriodRepository,
    private val transfers: TransferRepository,
    private val props: GatewayProperties,
) {

    /**
     * Waits for transfers of the run still being inserted, then closes the period: once this
     * returns, no new transfer of the run can commit. Idempotent. Called by the control service
     * when the client is done; an unknown run simply gets a closed, empty period.
     */
    @Transactional
    fun close(runId: UUID): RunPeriod {
        periods.find(runId)?.let { return it }
        periods.lockExclusive(runId, props.closeLockTimeout)
        return periods.insertIfAbsent(runId) ?: checkNotNull(periods.find(runId))
    }

    /** Only a closed period has a statement: before that, rows may still commit behind the cursor. */
    @Transactional(readOnly = true)
    fun statement(runId: UUID, after: UUID?, limit: Int): Statement {
        val period = periods.find(runId) ?: throw PeriodNotClosedException(runId)
        require(after == null || transfers.belongsTo(after, runId)) { "after must be a transfer of run $runId" }
        val rows = transfers.statementPage(runId, after, limit + 1)
        val items = rows.take(limit)
        return Statement(runId, period.closedAt, items, if (rows.size > limit) items.last().transferId else null)
    }
}
