package com.ledgerlab.generator.reconciliation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record TransferIds(
    long count,
    @Schema(description = "The first of them, by id; at most the generator's example limit.")
    List<UUID> transferIds
) {
    static final TransferIds NONE = new TransferIds(0, List.of());
}
