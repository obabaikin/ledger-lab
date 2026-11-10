package com.ledgerlab.gateway.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "One page of a closed period's statement.")
public record Statement(
    Instant periodStart,
    @Schema(description = "When the period was closed; every transfer accepted in it had been written by then.")
    Instant closedAt,
    @Schema(
        description = "Lines in the whole statement, any status; the same on every page.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    long entryCount,
    List<StatementLine> items,
    @Schema(description = "Pass as `after` for the next page; null on the last page.", nullable = true)
    @Nullable String next
) {
}
