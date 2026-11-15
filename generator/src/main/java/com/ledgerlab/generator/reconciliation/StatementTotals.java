package com.ledgerlab.generator.reconciliation;

import com.ledgerlab.generator.bank.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** {@link #byStatus} leaves out lines whose status this client cannot read; {@link #rows} counts them. */
public record StatementTotals(
    long rows,
    long amountCents,
    Map<TransferStatus, Long> byStatus,
    @Schema(description = "Lines whose transfer the client sent without learning the outcome, the generator's stop included.")
    long unknownToClient
) {
}
