package com.ledgerlab.ledger.persistence;

import java.time.Instant;
import java.util.UUID;

public record JournalEntry(Instant acceptedAt, UUID transferId, int accountNo, long amountCents) {
}
