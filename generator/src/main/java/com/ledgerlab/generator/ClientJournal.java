package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public interface ClientJournal {

    /**
     * Returns once the intent is stored for good; only then may the request leave.
     *
     * @throws JournalUnavailableException the intent was not stored: the transfer must not be sent
     */
    void intent(Instant sentAt, PlannedTransfer transfer) throws InterruptedException;

    /** Does not wait: an outcome that is lost leaves the intent unknown, and the bank is asked about it later. */
    void outcome(
        Instant sentAt,
        PlannedTransfer transfer,
        JournalOutcome outcome,
        @Nullable TransferStatus bankStatus,
        @Nullable RejectReason rejectReason,
        @Nullable Instant acceptedAt
    );
}
