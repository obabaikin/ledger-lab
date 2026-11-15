package com.ledgerlab.generator.bank;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Statement(
    long entryCount,
    List<StatementLine> items,
    // Opaque: handed back as it came to get the next page.
    @Nullable String next
) {
    public static final String ITEMS_FIELD = "items";

    public Statement {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
