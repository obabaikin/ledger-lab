package com.ledgerlab.reporting.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record TransferOutcomeRow(
    TransferKey key,
    String outcome,
    @Nullable String rejectReason,
    Instant processedAt
) {
}
