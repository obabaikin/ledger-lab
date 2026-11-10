package com.ledgerlab.gateway.persistence;

import com.ledgerlab.gateway.domain.ClosedPeriod;
import com.ledgerlab.gateway.domain.Period;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Accepting transfers holds one advisory lock shared, closing a period takes it exclusively for a moment (ADR 0009):
 * once the close has had it, every transfer stamped before that moment is committed or gone. The locks live in memory
 * and are released at commit or rollback.
 */
@Repository
public class PeriodRepository {

    public static final String ACCEPT_LOCK = "gateway.accept";

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;

    public PeriodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockAccepting() {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtextextended(:name, 0))").param("name", ACCEPT_LOCK).query(rs -> { });
    }

    /** By the database's clock, the one that stamps accepted_at. */
    public boolean hasEnded(Period period) {
        return jdbc.sql("SELECT clock_timestamp() >= :end").param("end", Sql.timestamptz(period.end())).query(Boolean.class).single();
    }

    public void awaitAcceptsInFlight(Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)").param("timeout", timeout.toMillis() + "ms").query(rs -> { });
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))").param("name", ACCEPT_LOCK).query(rs -> { });
        } catch (DataAccessException e) {
            // Spring leaves PostgreSQL's lock_timeout uncategorized.
            if (e.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                throw new CannotAcquireLockException("Inserts in flight did not finish within " + timeout, e);
            }
            throw e;
        }
    }

    public @Nullable ClosedPeriod find(Instant periodStart) {
        return jdbc.sql("SELECT * FROM periods WHERE period_start = :start")
            .param("start", Sql.timestamptz(periodStart))
            .query((rs, rowNum) -> toClosedPeriod(rs))
            .optional()
            .orElse(null);
    }

    public @Nullable ClosedPeriod insertIfAbsent(Period period) {
        return jdbc.sql(
            """
            INSERT INTO periods (period_start, closed_at, entry_count)
            SELECT :start, clock_timestamp(), count(*) FROM transfers WHERE accepted_at >= :start AND accepted_at < :end
            ON CONFLICT (period_start) DO NOTHING RETURNING *
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .query((rs, rowNum) -> toClosedPeriod(rs))
            .optional()
            .orElse(null);
    }

    public int deleteBefore(Instant periodStart) {
        return jdbc.sql("DELETE FROM periods WHERE period_start < :start").param("start", Sql.timestamptz(periodStart)).update();
    }

    private static ClosedPeriod toClosedPeriod(ResultSet rs) throws SQLException {
        return new ClosedPeriod(Sql.instant(rs, "period_start"), Sql.instant(rs, "closed_at"), rs.getLong("entry_count"));
    }
}
