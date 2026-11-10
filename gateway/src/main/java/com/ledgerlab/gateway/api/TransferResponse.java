package com.ledgerlab.gateway.api;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.gateway.domain.RejectReasons;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A transfer as the bank sees it now.")
public record TransferResponse(
    UUID transferId,
    UUID runId,
    TransferStatus status,
    @Schema(description = RejectReasons.REJECT_REASON_DESCRIPTION, nullable = true)
    @Nullable RejectReason rejectReason,
    Instant acceptedAt,
    @Schema(description = "When the final status was recorded; null while not final.", nullable = true)
    @Nullable Instant resultedAt
) {
    static TransferResponse of(Transfer transfer) {
        return new TransferResponse(
            transfer.transferId(), transfer.runId(), transfer.status(), transfer.rejectReason(), transfer.acceptedAt(), transfer.resultedAt());
    }
}
