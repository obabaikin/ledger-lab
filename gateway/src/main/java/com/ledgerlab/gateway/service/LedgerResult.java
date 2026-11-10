package com.ledgerlab.gateway.service;

import com.ledgerlab.common.TransferOutcome;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A result as the ledger sent it. The reason stays text: a ledger one version ahead may send one this gateway
 * does not know yet, and it is kept as received rather than lost (ADR 0011).
 */
public record LedgerResult(UUID runId, UUID transferId, TransferOutcome outcome, @Nullable String rejectReason) {
}
