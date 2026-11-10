package com.ledgerlab.ledger.persistence;

import java.util.UUID;

public record JournalEntry(UUID runId, UUID transferId, int accountNo, long amountCents) {
}
