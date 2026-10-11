package com.ledgerlab.gateway.persistence

import com.ledgerlab.gateway.domain.RunPeriod
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID

/**
 * Accepting a transfer holds the run's advisory lock shared, closing the period takes it
 * exclusively (ADR 0009). The locks live in memory and are released at commit or rollback.
 */
@Repository
class RunPeriodRepository(private val jdbc: JdbcClient) {

    fun lockShared(runId: UUID) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(:runId AS text), 0))")
            .param("runId", runId)
            .query { _, _ -> }
            .single()
    }

    fun lockExclusive(runId: UUID, timeout: Duration) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
            .param("timeout", "${timeout.toMillis()}ms")
            .query { _, _ -> }
            .single()
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:runId AS text), 0))")
                .param("runId", runId)
                .query { _, _ -> }
                .single()
        } catch (e: DataAccessException) {
            // Spring leaves PostgreSQL's lock_timeout uncategorized.
            if ((e.mostSpecificCause as? SQLException)?.sqlState == LOCK_NOT_AVAILABLE) {
                throw CannotAcquireLockException("Run $runId: inserts in flight did not finish within $timeout", e)
            }
            throw e
        }
    }

    fun find(runId: UUID): RunPeriod? =
        jdbc.sql("SELECT * FROM run_periods WHERE run_id = :runId")
            .param("runId", runId)
            .query { rs, _ -> rs.toRunPeriod() }
            .optional()
            .orElse(null)

    /**
     * Run after the lock was taken: now() is the transaction start, before inserts in flight committed,
     * while this statement's snapshot already sees them.
     */
    fun insertIfAbsent(runId: UUID): RunPeriod? =
        jdbc.sql(
            """
            INSERT INTO run_periods (run_id, closed_at, entry_count)
            SELECT :runId, clock_timestamp(), count(*) FROM transfers WHERE run_id = :runId
            ON CONFLICT (run_id) DO NOTHING RETURNING *
            """,
        )
            .param("runId", runId)
            .query { rs, _ -> rs.toRunPeriod() }
            .optional()
            .orElse(null)

    fun delete(runId: UUID): Int =
        jdbc.sql("DELETE FROM run_periods WHERE run_id = :runId").param("runId", runId).update()

    private fun java.sql.ResultSet.toRunPeriod() = RunPeriod(
        runId = getObject("run_id", UUID::class.java),
        closedAt = getObject("closed_at", Timestamp::class.java).toInstant(),
        entryCount = getLong("entry_count"),
    )

    private companion object {
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
