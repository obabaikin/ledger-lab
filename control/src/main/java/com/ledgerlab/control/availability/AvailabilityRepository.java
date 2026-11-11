package com.ledgerlab.control.availability;

import com.ledgerlab.control.generator.MinuteResponses;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AvailabilityRepository {

    /**
     * A minute is bad if fewer than :goodPercent of 100 requests got an answer, and planned if it lies in a window that
     * was not cancelled and was declared at least :noticeSeconds before its start.
     */
    private static final String CLASSIFIED_MINUTES = """
        SELECT m.minute, m.total, m.errors, m.busy,
               (m.total - m.errors) * 100 < m.total * :goodPercent AS bad,
               EXISTS (
                   SELECT 1 FROM maintenance_windows w
                   WHERE w.cancelled_at IS NULL AND w.starts_at <= m.minute AND m.minute < w.ends_at
                     AND w.declared_at <= w.starts_at - CAST(:noticeSeconds AS bigint) * interval '1 second') AS planned
        FROM availability_minutes m""";

    public record Counts(long measuredMinutes, long badMinutes, long plannedMinutes, long total, long busy) {
    }

    private final JdbcClient jdbc;

    public AvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public @Nullable Instant lastMinute() {
        return jdbc.sql("SELECT max(minute) FROM availability_minutes").query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
    }

    /** A minute without a request is not kept: it says nothing about the bank. */
    public int insert(List<MinuteResponses> minutes) {
        var inserted = 0;
        for (var minute : minutes) {
            if (minute.total() <= 0) {
                continue;
            }
            inserted += jdbc.sql(
                "INSERT INTO availability_minutes (minute, total, errors, busy) VALUES (:minute, :total, :errors, :busy) ON CONFLICT (minute) DO NOTHING")
                .param("minute", Timestamp.from(minute.minute()))
                .param("total", minute.total())
                .param("errors", minute.errors())
                .param("busy", minute.busy())
                .update();
        }
        return inserted;
    }

    /**
     * Adds up the hours that are over and whose minutes are all still kept; an hour older than that keeps the sums it has.
     * Done again for every such hour each time: a minute handed over late or a window declared since changes them.
     */
    public int rollUpHours(Instant now, Duration minutesRetention, int goodPercent, Duration notice) {
        return jdbc.sql(
            """
            INSERT INTO availability_hours (hour, measured_minutes, bad_minutes, planned_minutes, total, errors, busy)
            SELECT date_trunc('hour', c.minute, 'UTC'), count(*), count(*) FILTER (WHERE c.bad AND NOT c.planned),
                   count(*) FILTER (WHERE c.bad AND c.planned), sum(c.total), sum(c.errors), sum(c.busy)
            FROM (%s) c
            WHERE c.minute >= date_trunc('hour', CAST(:keptFrom AS timestamptz), 'UTC') + interval '1 hour'
              AND c.minute < date_trunc('hour', CAST(:now AS timestamptz), 'UTC')
            GROUP BY 1
            ON CONFLICT (hour) DO UPDATE SET measured_minutes = EXCLUDED.measured_minutes, bad_minutes = EXCLUDED.bad_minutes,
                planned_minutes = EXCLUDED.planned_minutes, total = EXCLUDED.total, errors = EXCLUDED.errors, busy = EXCLUDED.busy
            """.formatted(CLASSIFIED_MINUTES))
            .param("goodPercent", goodPercent)
            .param("noticeSeconds", notice.toSeconds())
            .param("keptFrom", Timestamp.from(now.minus(minutesRetention)))
            .param("now", Timestamp.from(now))
            .update();
    }

    /** The hours added up so far, and the minutes after the last of them; the window starts on the hour. */
    public Counts countsSince(Instant from, int goodPercent, Duration notice) {
        return jdbc.sql(
            """
            WITH rolled AS (
                SELECT coalesce(max(hour) + interval '1 hour', CAST('-infinity' AS timestamptz)) AS upto FROM availability_hours
            ), recent AS (
                SELECT count(*) AS measured, count(*) FILTER (WHERE c.bad AND NOT c.planned) AS bad,
                       count(*) FILTER (WHERE c.bad AND c.planned) AS planned, coalesce(sum(c.total), 0) AS total, coalesce(sum(c.busy), 0) AS busy
                FROM (%s) c, rolled
                WHERE c.minute >= rolled.upto AND c.minute >= date_trunc('hour', CAST(:from AS timestamptz), 'UTC')
            ), older AS (
                SELECT coalesce(sum(measured_minutes), 0) AS measured, coalesce(sum(bad_minutes), 0) AS bad,
                       coalesce(sum(planned_minutes), 0) AS planned, coalesce(sum(total), 0) AS total, coalesce(sum(busy), 0) AS busy
                FROM availability_hours WHERE hour >= date_trunc('hour', CAST(:from AS timestamptz), 'UTC')
            )
            SELECT recent.measured + older.measured AS measured, recent.bad + older.bad AS bad, recent.planned + older.planned AS planned,
                   recent.total + older.total AS total, recent.busy + older.busy AS busy
            FROM recent, older
            """.formatted(CLASSIFIED_MINUTES))
            .param("goodPercent", goodPercent)
            .param("noticeSeconds", notice.toSeconds())
            .param("from", Timestamp.from(from))
            .query((rs, rowNum) -> new Counts(rs.getLong("measured"), rs.getLong("bad"), rs.getLong("planned"), rs.getLong("total"), rs.getLong("busy")))
            .single();
    }

    public int deleteMinutesBefore(Instant before) {
        return jdbc.sql("DELETE FROM availability_minutes WHERE minute < date_trunc('hour', CAST(:before AS timestamptz), 'UTC')")
            .param("before", Timestamp.from(before))
            .update();
    }

    public @Nullable MaintenanceWindow nextWindow(Instant now) {
        return jdbc.sql("SELECT starts_at, ends_at FROM maintenance_windows WHERE cancelled_at IS NULL AND ends_at > :now ORDER BY starts_at LIMIT 1")
            .param("now", Timestamp.from(now))
            .query((rs, rowNum) -> new MaintenanceWindow(rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()))
            .optional()
            .orElse(null);
    }
}
