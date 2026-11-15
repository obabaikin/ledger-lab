package com.ledgerlab.gateway.persistence;

import com.ledgerlab.gateway.domain.Transfer;
import java.time.Instant;
import java.util.UUID;

public record TransferKey(Instant acceptedAt, UUID transferId) {

    public static TransferKey of(Transfer transfer) {
        return new TransferKey(transfer.acceptedAt(), transfer.transferId());
    }
}
