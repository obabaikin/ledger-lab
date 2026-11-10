package com.ledgerlab.gateway.domain;

import com.ledgerlab.common.RejectReason;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record Transfer(
    UUID transferId,
    UUID runId,
    int fromAccount,
    int toAccount,
    long amountCents,
    TransferStatus status,
    @Nullable RejectReason rejectReason,
    Instant acceptedAt,
    @Nullable Instant resultedAt
) {
}
