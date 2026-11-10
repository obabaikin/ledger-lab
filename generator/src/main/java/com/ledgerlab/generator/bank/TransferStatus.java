package com.ledgerlab.generator.bank;

public enum TransferStatus {
    ACCEPTED,
    PUBLISHED,
    POSTED,
    REJECTED;

    public boolean isFinal() {
        return this == POSTED || this == REJECTED;
    }
}
