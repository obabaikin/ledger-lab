package com.ledgerlab.generator.load;

import com.ledgerlab.generator.PlannedTransfer;
import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.journal.ClientJournal;
import com.ledgerlab.generator.journal.JournalOutcome;
import com.ledgerlab.generator.journal.JournalUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A client journal in memory, for tests of the load alone. */
final class RecordingJournal implements ClientJournal {

    record Outcome(JournalOutcome outcome, TransferStatus bankStatus, RejectReason rejectReason, Instant acceptedAt) {
    }

    final ConcurrentHashMap<UUID, PlannedTransfer> intents = new ConcurrentHashMap<>();
    final ConcurrentHashMap<UUID, Outcome> outcomes = new ConcurrentHashMap<>();
    volatile boolean unavailable = false;

    @Override
    public void intent(Instant sentAt, PlannedTransfer transfer) {
        if (unavailable) {
            throw new JournalUnavailableException("The test took the journal away", null);
        }
        intents.put(transfer.transferId(), transfer);
    }

    @Override
    public void outcome(
        Instant sentAt, PlannedTransfer transfer, JournalOutcome outcome, TransferStatus bankStatus, RejectReason rejectReason, Instant acceptedAt) {
        outcomes.put(transfer.transferId(), new Outcome(outcome, bankStatus, rejectReason, acceptedAt));
    }

    long count(JournalOutcome outcome) {
        return outcomes.values().stream().filter(it -> it.outcome() == outcome).count();
    }

    List<UUID> withoutOutcome() {
        return intents.keySet().stream().filter(id -> !outcomes.containsKey(id)).toList();
    }
}
