package com.ledgerlab.reporting.persistence;

import java.time.Instant;
import java.util.UUID;

public record AcceptedTransfer(UUID transferId, UUID runId, long amountCents, Instant acceptedAt) {
}
