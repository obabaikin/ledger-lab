package com.ledgerlab.generator;

/** What the journal holds about an intent; an intent without any of these is still unknown. */
public enum JournalOutcome {
    ACKNOWLEDGED,
    FAILED,
    /** The client did not learn it; asked later, the bank had the transfer. */
    UNKNOWN_ACCEPTED,
    /** The client did not learn it; asked later, the bank did not have the transfer. */
    UNKNOWN_NOT_ACCEPTED,
}
