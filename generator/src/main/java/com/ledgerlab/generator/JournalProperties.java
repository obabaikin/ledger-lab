package com.ledgerlab.generator;

import java.time.Duration;

public record JournalProperties(
    // How long after its run ends a journal is kept: long enough for the client reconciliation.
    Duration retention,
    // Entries all journals in memory may take together; a start beyond it is refused, never an OOM.
    long maxEntries
) {
}
