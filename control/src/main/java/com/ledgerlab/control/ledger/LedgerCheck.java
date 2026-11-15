package com.ledgerlab.control.ledger;

public enum LedgerCheck {
    JOURNAL_DEBITS_EQUAL_CREDITS,
    JOURNAL_MATCHES_TRANSFER_LOG,
    EVERY_ACCOUNT_MATCHES_JOURNAL,
}
