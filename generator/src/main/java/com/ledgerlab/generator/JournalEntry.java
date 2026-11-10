package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * One transfer as the client knows it. Its submission and its duplicate may record sends at the
 * same time; API threads read it, so every field a reader sees is published through a volatile write.
 */
public final class JournalEntry implements ReconciledEntry {

    private final long seq;
    private final PlannedTransfer transfer;
    private final long runStartNanos;
    private volatile JournalOutcome outcome = JournalOutcome.PENDING;
    private volatile @Nullable TransferStatus gatewayStatus = null;
    private volatile @Nullable RejectReason rejectReason = null;
    private volatile @Nullable Send first = null;
    private volatile List<Resend> resends = List.of();

    public JournalEntry(long seq, PlannedTransfer transfer, long runStartNanos) {
        this.seq = seq;
        this.transfer = transfer;
        this.runStartNanos = runStartNanos;
    }

    @Override
    public long seq() {
        return seq;
    }

    @Override
    public JournalOutcome outcome() {
        return outcome;
    }

    @Override
    public @Nullable TransferStatus gatewayStatus() {
        return gatewayStatus;
    }

    @Override
    public @Nullable RejectReason rejectReason() {
        return rejectReason;
    }

    @Override
    public UUID transferId() {
        return transfer.transferId();
    }

    @Override
    public int fromAccount() {
        return transfer.fromAccount();
    }

    @Override
    public int toAccount() {
        return transfer.toAccount();
    }

    @Override
    public long amountCents() {
        return transfer.amountCents();
    }

    /** Resends are numbered in the order they end, so the list only grows at its end while it is read. */
    public synchronized void record(SendKind kind, long startedNanos, long endedNanos, SendResult result, @Nullable Integer httpStatus) {
        var offsetMs = TimeUnit.NANOSECONDS.toMillis(startedNanos - runStartNanos);
        var latencyMs = TimeUnit.NANOSECONDS.toMillis(endedNanos - startedNanos);
        var resendKind = switch (kind) {
            case FIRST -> {
                first = new Send(offsetMs, result, httpStatus, latencyMs);
                yield null;
            }
            case RETRY -> ResendKind.RETRY;
            case DUPLICATE -> ResendKind.DUPLICATE;
        };
        if (resendKind == null) {
            return;
        }
        var grown = new ArrayList<>(resends);
        grown.add(new Resend(resendKind, GatewayClient.FIRST_ATTEMPT + resends.size() + 1, offsetMs, result, httpStatus, latencyMs));
        resends = List.copyOf(grown);
    }

    public void settle(JournalOutcome outcome) {
        settle(outcome, null, null);
    }

    public void settle(JournalOutcome outcome, @Nullable TransferStatus gatewayStatus, @Nullable RejectReason rejectReason) {
        this.gatewayStatus = gatewayStatus;
        this.rejectReason = rejectReason;
        this.outcome = outcome;
    }

    public JournalEntryView view() {
        return new JournalEntryView(
            seq, transfer.transferId(), transfer.fromAccount(), transfer.toAccount(), transfer.amountCents(),
            outcome, gatewayStatus, rejectReason, first, resends);
    }
}
