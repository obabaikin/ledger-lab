package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What the client reconciliation reads of one transfer. */
public interface ReconciledEntry {
    long seq();

    UUID transferId();

    int fromAccount();

    int toAccount();

    long amountCents();

    JournalOutcome outcome();

    @Nullable TransferStatus gatewayStatus();

    @Nullable RejectReason rejectReason();
}
