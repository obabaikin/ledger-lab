package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record JournalEntryView(
    long seq,
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents,
    JournalOutcome outcome,
    @Schema(nullable = true) @Nullable TransferStatus gatewayStatus,
    @Schema(nullable = true) @Nullable RejectReason rejectReason,
    // The first attempt of the submission, even when a duplicate ended earlier; null while it waits for its answer.
    @Schema(nullable = true) @Nullable Send first,
    List<Resend> resends
) implements ReconciledEntry {
}
