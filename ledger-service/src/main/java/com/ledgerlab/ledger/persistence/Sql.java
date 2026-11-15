package com.ledgerlab.ledger.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;

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
}
