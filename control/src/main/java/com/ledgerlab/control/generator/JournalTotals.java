package com.ledgerlab.control.generator;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record JournalTotals(
    long requested,
    long acknowledged,
    long failed,
    long unknown,
    long acknowledgedAmountCents,
    // Reports stored before the count existed have none.
    @Schema(description = GeneratorApi.REFUSED_DESCRIPTION)
    long refused
) {
    @JsonCreator
    static JournalTotals read(long requested, long acknowledged, long failed, long unknown, long acknowledgedAmountCents, @Nullable Long refused) {
        return new JournalTotals(requested, acknowledged, failed, unknown, acknowledgedAmountCents, refused != null ? refused : 0);
    }
}
