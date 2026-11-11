package com.ledgerlab.control.periods;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Schema(description = "A period's transfers by outcome.")
public record PeriodNumbers(
    long accepted,
    long posted,
    long rejected,
    @Schema(description = "Sum of the posted transfers; 0 if the reporting service could not be read.")
    long postedAmountCents,
    Map<String, Long> rejectionsByReason
) {

    public static final PeriodNumbers NONE = new PeriodNumbers(0, 0, 0, 0, Map.of());

    public PeriodNumbers {
        rejectionsByReason = Map.copyOf(Objects.requireNonNull(rejectionsByReason, "rejectionsByReason"));
    }

    public PeriodNumbers plus(PeriodNumbers other) {
        var reasons = new TreeMap<>(rejectionsByReason);
        other.rejectionsByReason.forEach((reason, count) -> reasons.merge(reason, count, Long::sum));
        return new PeriodNumbers(
            accepted + other.accepted, posted + other.posted, rejected + other.rejected, postedAmountCents + other.postedAmountCents, reasons);
    }
}
