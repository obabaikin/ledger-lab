package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.TransferCommand;
import java.time.Instant;
import java.util.UUID;

public record TransferKey(Instant acceptedAt, UUID transferId) {

    public static TransferKey of(TransferCommand command) {
        return new TransferKey(command.acceptedAt(), command.transferId());
    }
}
