package com.ledgerlab.control.generator;

import java.util.List;
import java.util.Objects;

public record TransferSides(long count, List<MismatchedTransfer> examples) {

    public TransferSides {
        examples = List.copyOf(Objects.requireNonNull(examples, "examples"));
    }
}
