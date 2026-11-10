package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.TransferStatus;
import java.util.Map;

/** {@link #byStatus} leaves out lines whose status this client cannot read; {@link #rows} counts them. */
public record StatementTotals(long rows, long amountCents, Map<TransferStatus, Long> byStatus) {
}
