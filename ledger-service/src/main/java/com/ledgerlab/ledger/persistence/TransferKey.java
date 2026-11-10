package com.ledgerlab.ledger.persistence;

import java.util.UUID;

public record TransferKey(UUID runId, UUID transferId) {
}
