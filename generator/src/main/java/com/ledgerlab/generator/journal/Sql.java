package com.ledgerlab.generator.journal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

public final class Sql {

    private Sql() {
    }

    /** The driver binds no Instant. */
    public static OffsetDateTime timestamptz(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static @Nullable String nullableText(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    public static @Nullable String nullableName(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }
}
