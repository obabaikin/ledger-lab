package com.ledgerlab.ledger.persistence;

import java.time.Instant;

public record PoolState(int nextAccountNo, Instant roundAdvancedAt, Instant hotSweptAt) {
}
