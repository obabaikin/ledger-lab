package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

public record SubmissionResult(
    // Null when the answer could not be read, e.g. a status this client does not know yet.
    @Nullable TransferStatus status,
    @Nullable RejectReason rejectReason,
    int attempts,
    SubmissionOutcome outcome,
    Duration latency,
    // Attempts the gateway refused as busy: it did not take the transfer.
    int refusals
) {
}
