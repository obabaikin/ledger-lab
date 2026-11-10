package com.ledgerlab.control.chaos;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChaosRepository {

    private static final String CHAOS_LOCK = "control.chaos";

    private final JdbcClient jdbc;

    public ChaosRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", CHAOS_LOCK)
            .query(rs -> {
            });
    }

    public @Nullable Instant lastAt() {
        return jdbc.sql("SELECT at FROM chaos_events ORDER BY at DESC LIMIT 1")
            .query(Timestamp.class)
            .optional()
            .map(Timestamp::toInstant)
            .orElse(null);
    }

    public void insert(ChaosEvent event) {
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (:at, :instances)")
            .param("at", Timestamp.from(event.at()))
            .param("instances", event.instances().toArray(String[]::new))
            .update();
    }

    public List<ChaosEvent> since(Instant from) {
        return jdbc.sql("SELECT at, instances FROM chaos_events WHERE at >= :from ORDER BY at")
            .param("from", Timestamp.from(from))
            .query((rs, rowNum) -> toEvent(rs))
            .list();
    }

    public int deleteOlderThan(Duration retention) {
        return jdbc.sql("DELETE FROM chaos_events WHERE at <= clock_timestamp() - CAST(:retentionMillis AS bigint) * interval '1 millisecond'")
            .param("retentionMillis", retention.toMillis())
            .update();
    }

    private static ChaosEvent toEvent(ResultSet rs) throws SQLException {
        return new ChaosEvent(
            rs.getTimestamp("at").toInstant(),
            Arrays.stream((Object[]) rs.getArray("instances").getArray()).map(it -> (String) it).toList());
    }
}
