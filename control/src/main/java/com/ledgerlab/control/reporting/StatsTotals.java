package com.ledgerlab.control.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Objects;

public record StatsTotals(
    @Schema(description = "Transfers the gateway accepted, including those known only by their result so far.")
    long accepted,
    @Schema(description = "Transfers whose command is seen: their amount and acceptedAt are known.")
    long commandsSeen,
    long posted,
    long rejected,
    @Schema(description = "accepted − posted − rejected.")
    long inFlight,
    @Schema(description = "Sum of posted transfers whose amount is known.")
    long postedAmountCents,
    Map<String, Long> rejectedByReason
) {
    public StatsTotals {
        Objects.requireNonNull(rejectedByReason, "rejectedByReason");
    }
}
