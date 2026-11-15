package com.ledgerlab.control.generator;

import java.util.Objects;
import java.util.UUID;

public record MismatchedTransfer(UUID transferId, TransferSide client, TransferSide bank) {

    public MismatchedTransfer {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(bank, "bank");
    }
}
