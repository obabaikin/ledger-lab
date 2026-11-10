package com.ledgerlab.gateway.persistence;

import com.ledgerlab.gateway.domain.RunPeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Accepting a transfer holds the run's advisory lock shared, closing the period takes it
 * exclusively (ADR 0009). The locks live in memory and are released at commit or rollback.
 */
@Repository
public class RunPeriodRepository {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;

    public RunPeriodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockShared(UUID runId) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(:runId AS text), 0))")
            .param("runId", runId)
            .query(rs -> {
            });
    }

    public void lockExclusive(UUID runId, Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
            .param("timeout", timeout.toMillis() + "ms")
            .query(rs -> {
            });
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:runId AS text), 0))")
                .param("runId", runId)
                .query(rs -> {
                });
        } catch (DataAccessException e) {
            // Spring leaves PostgreSQL's lock_timeout uncategorized.
            if (e.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                throw new CannotAcquireLockException("Run " + runId + ": inserts in flight did not finish within " + timeout, e);
            }
            throw e;
        }
    }

    public @Nullable RunPeriod find(UUID runId) {
        return jdbc.sql("SELECT * FROM run_periods WHERE run_id = :runId")
            .param("runId", runId)
            .query((rs, rowNum) -> toRunPeriod(rs))
            .optional()
            .orElse(null);
    }

    public Set<UUID> closedAmong(Set<UUID> runIds) {
        if (runIds.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql("SELECT run_id FROM run_periods WHERE run_id = ANY(CAST(:ids AS uuid[]))")
            .param("ids", runIds.stream().map(UUID::toString).toArray(String[]::new))
            .query((rs, rowNum) -> rs.getObject("run_id", UUID.class))
            .set();
    }

    /**
     * Run after the lock was taken: now() is the transaction start, before inserts in flight committed,
     * while this statement's snapshot already sees them.
     */
    public @Nullable RunPeriod insertIfAbsent(UUID runId) {
        return jdbc.sql(
            """
            INSERT INTO run_periods (run_id, closed_at, entry_count)
            SELECT :runId, clock_timestamp(), count(*) FROM transfers WHERE run_id = :runId
            ON CONFLICT (run_id) DO NOTHING RETURNING *
            """)
            .param("runId", runId)
            .query((rs, rowNum) -> toRunPeriod(rs))
            .optional()
            .orElse(null);
    }

    public int delete(UUID runId) {
        return jdbc.sql("DELETE FROM run_periods WHERE run_id = :runId").param("runId", runId).update();
    }

    private static RunPeriod toRunPeriod(ResultSet rs) throws SQLException {
        return new RunPeriod(
            rs.getObject("run_id", UUID.class),
            rs.getObject("closed_at", Timestamp.class).toInstant(),
            rs.getLong("entry_count"));
    }
}
