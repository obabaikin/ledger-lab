package com.ledgerlab.generator.journal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What became of the clients' transfers since the bank started, whichever generator sent them.")
public record ClientTotals(
    @Schema(description = "Transfers the clients sent: each intent the journal stored.")
    long sent,
    @Schema(description = "Transfers the bank took (any 2xx).")
    long acknowledged,
    @Schema(description = "Transfers whose client stopped trying: refused for good, or without an answer, whatever the bank said when asked later.")
    long gaveUp,
    @Schema(description = "Sent, without an outcome yet: in flight, or given up and not yet asked about.")
    long unanswered
) {
}
