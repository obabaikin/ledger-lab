package com.ledgerlab.generator;

/** Why a reconciliation did not produce a report; asking for it again starts it anew. */
public enum ReconciliationFailure {
    PERIOD_NOT_CLOSED,
    GATEWAY_UNAVAILABLE,
    BAD_GATEWAY_ANSWER,
    JOURNAL_UNAVAILABLE,
    INTERNAL_ERROR,
}
