package com.ledgerlab.reporting.persistence;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TransferOutcomeRow(
    UUID transferId,
    UUID runId,
    String outcome,
    @Nullable String rejectReason,
    Instant processedAt
) {
}
