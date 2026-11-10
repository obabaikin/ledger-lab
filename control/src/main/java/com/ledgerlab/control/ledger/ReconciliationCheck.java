package com.ledgerlab.control.ledger;

public enum ReconciliationCheck {
    TOTAL_BALANCE_CONSERVED,
    JOURNAL_SUMS_TO_ZERO,
    JOURNAL_MATCHES_TRANSFER_LOG,
    EVERY_ACCOUNT_MATCHES_JOURNAL,
}
