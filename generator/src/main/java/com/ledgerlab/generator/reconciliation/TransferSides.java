package com.ledgerlab.generator.reconciliation;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "Transfers the client and the bank hold differently.")
public record TransferSides(
    long count,
    @Schema(description = "The first of them, by id; at most the generator's example limit.")
    List<MismatchedTransfer> examples
) {
    static final TransferSides NONE = new TransferSides(0, List.of());

    public record MismatchedTransfer(UUID transferId, TransferSide client, TransferSide bank) {
    }

    @Schema(description = "One transfer as one side holds it.")
    public record TransferSide(
        int fromAccount,
        int toAccount,
        long amountCents,
        @Schema(nullable = true) @Nullable TransferStatus status,
        @Schema(nullable = true) @Nullable RejectReason rejectReason
    ) {
    }
}
