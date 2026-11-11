package com.ledgerlab.reporting.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

@Schema(description = "The transfers accepted in a period as seen in the bank's events; one consistent snapshot, lagging the books while events are on the way.")
public record PeriodStats(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Instant periodStart,
    @Schema(description = "Time of the snapshot, by the reporting database's clock.", requiredMode = Schema.RequiredMode.REQUIRED)
    Instant at,
    @Schema(description = "Transfers the gateway accepted, including those known only by their result so far.", requiredMode = Schema.RequiredMode.REQUIRED)
    long accepted,
    @Schema(description = "Transfers whose command is seen: their amount is known.", requiredMode = Schema.RequiredMode.REQUIRED)
    long commandsSeen,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    long posted,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    long rejected,
    @Schema(description = "Sum of posted transfers whose amount is known.", requiredMode = Schema.RequiredMode.REQUIRED)
    long postedAmountCents,
    @Schema(description = "Rejected transfers by reason; a reason this service does not know is kept as sent.", requiredMode = Schema.RequiredMode.REQUIRED)
    Map<String, Long> rejectionsByReason
) {
}
