package com.ledgerlab.control.generator;

import java.util.Map;
import java.util.Objects;

public record StatementTotals(long rows, long amountCents, Map<String, Long> byStatus, long unknownToClient) {

    public StatementTotals {
        byStatus = Map.copyOf(Objects.requireNonNull(byStatus, "byStatus"));
    }
}
