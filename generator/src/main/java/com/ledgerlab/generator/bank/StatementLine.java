package com.ledgerlab.generator.bank;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record StatementLine(
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents,
    // Null if the bank is newer than this client and sent a status it does not know (ADR 0010).
    @Nullable TransferStatus status,
    @Nullable RejectReason rejectReason
) {
    public static final String REJECT_REASON_FIELD = "rejectReason";

    public StatementLine {
        Objects.requireNonNull(transferId, "transferId");
    }
}
