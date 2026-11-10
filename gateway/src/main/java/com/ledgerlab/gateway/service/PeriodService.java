package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.domain.RunPeriod;
import com.ledgerlab.gateway.domain.Statement;
import com.ledgerlab.gateway.persistence.RunPeriodRepository;
import com.ledgerlab.gateway.persistence.TransferRepository;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PeriodService {

    private final RunPeriodRepository periods;
    private final TransferRepository transfers;
    private final GatewayProperties props;

    public PeriodService(RunPeriodRepository periods, TransferRepository transfers, GatewayProperties props) {
        this.periods = periods;
        this.transfers = transfers;
        this.props = props;
    }

    /** Waits for inserts in flight; once this returns, no new transfer of the run can commit (ADR 0009). */
    @Transactional
    public RunPeriod close(UUID runId) {
        var closed = periods.find(runId);
        if (closed != null) {
            return closed;
        }
        periods.lockExclusive(runId, props.closeLockTimeout());
        var inserted = periods.insertIfAbsent(runId);
        return inserted != null ? inserted : Objects.requireNonNull(periods.find(runId), "the period someone else closed meanwhile");
    }

    /** Only a closed period has a statement: before that, rows may still commit behind the cursor. */
    @Transactional(readOnly = true)
    public Statement statement(UUID runId, @Nullable UUID after, int limit) {
        var period = periods.find(runId);
        if (period == null) {
            throw new PeriodNotClosedException(runId);
        }
        if (after != null && !transfers.belongsTo(after, runId)) {
            throw new IllegalArgumentException("after must be a transfer of run " + runId);
        }
        var rows = transfers.statementPage(runId, after, limit + 1);
        var items = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new Statement(runId, period.closedAt(), period.entryCount(), items, rows.size() > limit ? items.getLast().transferId() : null);
    }
}
