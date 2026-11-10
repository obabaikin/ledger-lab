package com.ledgerlab.gateway.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "One page of a closed period's statement.")
public record Statement(
    UUID runId,
    @Schema(description = "When the period was closed; no transfer of the run is accepted after it.")
    Instant closedAt,
    @Schema(
        description = "Lines in the whole statement, any status; the same on every page.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    long entryCount,
    List<StatementLine> items,
    @Schema(description = "Pass as `after` for the next page; null on the last page.", nullable = true)
    @Nullable UUID next
) {
}
