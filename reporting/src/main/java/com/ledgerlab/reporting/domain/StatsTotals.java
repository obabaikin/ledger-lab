package com.ledgerlab.reporting.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

public record StatsTotals(
    @Schema(description = "Transfers the gateway accepted, including those known only by their result so far.", requiredMode = Schema.RequiredMode.REQUIRED)
    long accepted,
    @Schema(description = "Transfers whose command is seen: their amount and acceptedAt are known.", requiredMode = Schema.RequiredMode.REQUIRED)
    long commandsSeen,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    long posted,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    long rejected,
    @Schema(description = "accepted − posted − rejected.", requiredMode = Schema.RequiredMode.REQUIRED)
    long inFlight,
    @Schema(description = "Sum of posted transfers whose amount is known.", requiredMode = Schema.RequiredMode.REQUIRED)
    long postedAmountCents,
    @Schema(description = "Rejected transfers by reason; a reason this service does not know is kept as sent.", requiredMode = Schema.RequiredMode.REQUIRED)
    Map<String, Long> rejectedByReason
) {
}
