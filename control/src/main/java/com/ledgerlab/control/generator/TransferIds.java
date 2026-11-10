package com.ledgerlab.control.generator;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TransferIds(long count, List<UUID> transferIds) {
    public TransferIds {
        transferIds = List.copyOf(Objects.requireNonNull(transferIds, "transferIds"));
    }
}
