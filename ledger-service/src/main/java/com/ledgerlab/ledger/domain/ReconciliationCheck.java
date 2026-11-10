package com.ledgerlab.ledger.domain;

public enum ReconciliationCheck {
    TOTAL_BALANCE_CONSERVED("total balance equals total opening balance"),
    JOURNAL_SUMS_TO_ZERO("journal sums to zero"),
    JOURNAL_MATCHES_TRANSFER_LOG("every posted transfer has exactly the debit and credit its log entry implies; rejected transfers have none"),
    EVERY_ACCOUNT_MATCHES_JOURNAL("every account balance equals opening balance plus its journal");

    private final String description;

    ReconciliationCheck(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
