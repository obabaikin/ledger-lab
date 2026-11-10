package com.ledgerlab.generator.bank;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record Statement(
    long entryCount,
    List<StatementLine> items,
    @Nullable UUID next
) {
    public static final String ITEMS_FIELD = "items";

    public Statement {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
