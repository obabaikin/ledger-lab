package com.ledgerlab.control;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The clock the bank's services cut their periods by: they share this database. */
@Repository
public class DatabaseClock {

    private final JdbcClient jdbc;

    public DatabaseClock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }
}
