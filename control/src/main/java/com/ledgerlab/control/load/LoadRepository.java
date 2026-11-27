package com.ledgerlab.control.load;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LoadRepository {

    private static final String LOAD_LOCK = "control.load";

    private final JdbcClient jdbc;

    public LoadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False at once when another press holds the lock; held until this transaction ends. */
    public boolean tryLock() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", LOAD_LOCK)
            .query(Boolean.class)
            .single();
    }

    public @Nullable Instant lastUntil() {
        return jdbc.sql("SELECT until FROM load_pulses ORDER BY until DESC LIMIT 1")
            .query(Timestamp.class)
            .optional()
            .map(Timestamp::toInstant)
            .orElse(null);
    }

    /** The rule of AvailabilityRepository's outside minutes for a pulse, at this instant of the database clock. */
    public boolean aboveNow(int guaranteedTps, Duration aftermath) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM load_pulses
                    WHERE tps > :guaranteedTps AND at <= clock_timestamp()
                      AND clock_timestamp() < until + CAST(:aftermathMillis AS bigint) * interval '1 millisecond')""")
            .param("guaranteedTps", guaranteedTps)
            .param("aftermathMillis", aftermath.toMillis())
            .query(Boolean.class)
            .single();
    }

    public void insert(LoadPulse pulse) {
        jdbc.sql("INSERT INTO load_pulses (at, tps, seconds, until) VALUES (:at, :tps, :seconds, :until)")
            .param("at", Timestamp.from(pulse.at()))
            .param("tps", pulse.tps())
            .param("seconds", pulse.seconds())
            .param("until", Timestamp.from(pulse.until()))
            .update();
    }

    public int deleteOlderThan(Duration retention) {
        return jdbc.sql("DELETE FROM load_pulses WHERE until <= clock_timestamp() - CAST(:retentionMillis AS bigint) * interval '1 millisecond'")
            .param("retentionMillis", retention.toMillis())
            .update();
    }
}
