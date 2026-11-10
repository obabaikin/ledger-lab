package com.ledgerlab.control.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record SecondStats(
    @Schema(description = "Start of the second.")
    Instant second,
    @Schema(description = "By acceptedAt, or by processedAt while the command is not seen yet.")
    long accepted,
    @Schema(description = "By processedAt, as the rest.")
    long posted,
    long rejected,
    long postedAmountCents,
    @Schema(nullable = true) @Nullable Double bankP50Ms,
    @Schema(nullable = true) @Nullable Double bankP99Ms
) {
    public SecondStats {
        Objects.requireNonNull(second, "second");
    }
}
