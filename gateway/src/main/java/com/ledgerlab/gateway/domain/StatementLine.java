package com.ledgerlab.gateway.domain;

import com.ledgerlab.common.RejectReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A transfer the bank accepted in the closed period.")
public record StatementLine(
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents,
    TransferStatus status,
    @Schema(description = RejectReasons.REJECT_REASON_DESCRIPTION, nullable = true)
    @Nullable RejectReason rejectReason,
    Instant acceptedAt,
    @Schema(description = "When the final status was recorded; null while not final.", nullable = true)
    @Nullable Instant resultedAt
) {
}
