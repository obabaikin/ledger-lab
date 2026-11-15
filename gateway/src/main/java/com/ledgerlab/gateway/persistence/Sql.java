package com.ledgerlab.gateway.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

final class Sql {

    private Sql() {
    }

    /** The driver binds no Instant. */
    static OffsetDateTime timestamptz(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static String[] timestamptzArray(Collection<Instant> instants) {
        return instants.stream().map(Instant::toString).toArray(String[]::new);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
