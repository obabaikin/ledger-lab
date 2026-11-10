package com.ledgerlab.generator;

import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** What the client reconciliation reads of a journal: the entries themselves, so a large run is not copied. */
public interface ReconciledJournal {
    int size();

    @Nullable ReconciledEntry find(UUID transferId);

    void forEach(Consumer<ReconciledEntry> action);
}
