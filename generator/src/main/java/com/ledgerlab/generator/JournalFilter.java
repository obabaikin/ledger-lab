package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import org.jspecify.annotations.Nullable;

public record JournalFilter(
    @Nullable JournalOutcome outcome,
    @Nullable Boolean resent,
    @Nullable RejectReason rejectReason
) {
    public boolean matches(JournalEntryView entry) {
        return (outcome == null || entry.outcome() == outcome)
            && (resent == null || !entry.resends().isEmpty() == resent)
            && (rejectReason == null || entry.rejectReason() == rejectReason);
    }
}
