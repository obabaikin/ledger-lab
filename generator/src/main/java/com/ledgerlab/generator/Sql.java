package com.ledgerlab.generator;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

final class Sql {

    private Sql() {
    }

    /** The driver binds no Instant. */
    static OffsetDateTime timestamptz(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static @Nullable OffsetDateTime nullableTimestamptz(@Nullable Instant instant) {
        return instant == null ? null : timestamptz(instant);
    }

    static @Nullable String nullableName(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }
}
