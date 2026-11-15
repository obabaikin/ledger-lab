package com.ledgerlab.control.generator;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What became of the clients' transfers since the bank started, whichever generator sent them.")
public record ClientTotals(
    @Schema(description = "Transfers the clients sent.")
    long sent,
    @Schema(description = "Transfers the bank took (any 2xx).")
    long acknowledged,
    @Schema(description = "Transfers whose client stopped trying: refused for good, or without an answer it could use.")
    long gaveUp,
    @Schema(description = "Sent, without an outcome yet: in flight, or given up and not yet asked about.")
    long unanswered
) {
}
