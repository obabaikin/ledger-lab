package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import org.jspecify.annotations.Nullable;

public record TransferRow(TransferCommand command, @Nullable RejectReason rejectReason) {
}
