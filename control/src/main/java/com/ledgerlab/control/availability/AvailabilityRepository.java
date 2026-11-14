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
     * was not cancelled and was declared at least :noticeSeconds before its start. It is fresh-bad if more than
     * :badSharePercent of 100 of its transfers were slow; one without transfers or not read from monitoring is not
     * measured for freshness. It is outside if it meets a crash or a pulse above :guaranteedTps, each with its aftermath,
     * during which no service restarted in place: a minute meets an event if it starts before the event's end and ends
     * after its start. The events' windows are joined to the minutes once: a subquery per minute, repeated in every
     * count that reads it, cost the roll-up half a second of JIT compilation.
     */
    private static final String CLASSIFIED_MINUTES = """
        WITH event_windows AS (
            SELECT e.at AS starts, e.at + CAST(:chaosAftermathSeconds AS bigint) * interval '1 second' AS ends FROM chaos_events e
            UNION ALL
            SELECT p.at, p.until + CAST(:loadAftermathSeconds AS bigint) * interval '1 second' FROM load_pulses p WHERE p.tps > :guaranteedTps
        ), clean_windows AS (
            SELECT w.starts, w.ends FROM event_windows w
            WHERE NOT EXISTS (
                SELECT 1 FROM availability_minutes r
                WHERE r.restarted AND r.minute > w.starts - interval '1 minute' AND r.minute < w.ends)
        ), outside_minutes AS (
            SELECT DISTINCT o.minute FROM clean_windows w
            JOIN availability_minutes o ON o.minute > w.starts - interval '1 minute' AND o.minute < w.ends
        )
        SELECT m.minute, m.total, m.errors, m.busy,
               (m.total - m.errors) * 100 < m.total * :goodPercent AS bad,
               coalesce(m.transfers > 0, false) AS fresh_measured,
               coalesce(m.transfers > 0 AND m.slow * 100 > m.transfers * :badSharePercent, false) AS fresh_bad,
               EXISTS (
                   SELECT 1 FROM maintenance_windows w
                   WHERE w.cancelled_at IS NULL AND w.starts_at <= m.minute AND m.minute < w.ends_at
                     AND w.declared_at <= w.starts_at - CAST(:noticeSeconds AS bigint) * interval '1 second') AS planned,
               o.minute IS NOT NULL AS outside
        FROM availability_minutes m LEFT JOIN outside_minutes o ON o.minute = m.minute""";

    public record Rules(
        int goodPercent,
        Duration notice,
        int badSharePercent,
        int guaranteedTps,
        Duration chaosAftermath,
        Duration loadAftermath
    ) {
    }

    /**
     * Bad minutes split three ways: {@code bad} use up the budget, {@code planned} lie in a maintenance window and
     * {@code outside} outside the bounds; a planned minute that is also outside counts as planned.
     */
    public record Counts(
        long measuredMinutes,
        long badMinutes,
        long plannedMinutes,
        long outsideMinutes,
        long total,
        long busy,
        long freshMeasured,
        long freshBad,
        long freshPlanned,
        long freshOutside
    ) {
    }

    /** One minute as monitoring tells it; {@code restarted} is null if monitoring had no process to tell about. */
    public record FreshMinute(Instant minute, long transfers, long slow, @Nullable Boolean restarted) {
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
    public int rollUpHours(Instant now, Duration minutesRetention, Rules rules) {
        return withRules(jdbc.sql(
            """
            INSERT INTO availability_hours (hour, measured_minutes, bad_minutes, planned_minutes, outside_minutes, total, errors, busy,
                                            fresh_measured, fresh_bad, fresh_planned, fresh_outside)
            SELECT date_trunc('hour', c.minute, 'UTC'), count(*),
                   count(*) FILTER (WHERE c.bad AND NOT c.planned AND NOT c.outside),
                   count(*) FILTER (WHERE c.bad AND c.planned),
                   count(*) FILTER (WHERE c.bad AND NOT c.planned AND c.outside),
                   sum(c.total), sum(c.errors), sum(c.busy),
                   count(*) FILTER (WHERE c.fresh_measured),
                   count(*) FILTER (WHERE c.fresh_bad AND NOT c.planned AND NOT c.outside),
                   count(*) FILTER (WHERE c.fresh_bad AND c.planned),
                   count(*) FILTER (WHERE c.fresh_bad AND NOT c.planned AND c.outside)
            FROM (%s) c
            WHERE c.minute >= date_trunc('hour', CAST(:keptFrom AS timestamptz), 'UTC') + interval '1 hour'
              AND c.minute < date_trunc('hour', CAST(:now AS timestamptz), 'UTC')
            GROUP BY 1
            ON CONFLICT (hour) DO UPDATE SET measured_minutes = EXCLUDED.measured_minutes, bad_minutes = EXCLUDED.bad_minutes,
                planned_minutes = EXCLUDED.planned_minutes, outside_minutes = EXCLUDED.outside_minutes, total = EXCLUDED.total,
                errors = EXCLUDED.errors, busy = EXCLUDED.busy, fresh_measured = EXCLUDED.fresh_measured, fresh_bad = EXCLUDED.fresh_bad,
                fresh_planned = EXCLUDED.fresh_planned, fresh_outside = EXCLUDED.fresh_outside
            """.formatted(CLASSIFIED_MINUTES)), rules)
            .param("keptFrom", Timestamp.from(now.minus(minutesRetention)))
            .param("now", Timestamp.from(now))
            .update();
    }

    /** The hours added up so far, and the minutes after the last of them; the window starts on the hour. */
    public Counts countsSince(Instant from, Rules rules) {
        return withRules(jdbc.sql(
            """
            WITH recent AS (
                SELECT count(*) AS measured,
                       count(*) FILTER (WHERE c.bad AND NOT c.planned AND NOT c.outside) AS bad,
                       count(*) FILTER (WHERE c.bad AND c.planned) AS planned,
                       count(*) FILTER (WHERE c.bad AND NOT c.planned AND c.outside) AS outside,
                       coalesce(sum(c.total), 0) AS total, coalesce(sum(c.busy), 0) AS busy,
                       count(*) FILTER (WHERE c.fresh_measured) AS fresh_measured,
                       count(*) FILTER (WHERE c.fresh_bad AND NOT c.planned AND NOT c.outside) AS fresh_bad,
                       count(*) FILTER (WHERE c.fresh_bad AND c.planned) AS fresh_planned,
                       count(*) FILTER (WHERE c.fresh_bad AND NOT c.planned AND c.outside) AS fresh_outside
                FROM (%s) c
                WHERE c.minute >= (SELECT coalesce(max(hour) + interval '1 hour', CAST('-infinity' AS timestamptz)) FROM availability_hours)
                  AND c.minute >= date_trunc('hour', CAST(:from AS timestamptz), 'UTC')
            ), older AS (
                SELECT coalesce(sum(measured_minutes), 0) AS measured, coalesce(sum(bad_minutes), 0) AS bad,
                       coalesce(sum(planned_minutes), 0) AS planned, coalesce(sum(outside_minutes), 0) AS outside,
                       coalesce(sum(total), 0) AS total, coalesce(sum(busy), 0) AS busy,
                       coalesce(sum(fresh_measured), 0) AS fresh_measured, coalesce(sum(fresh_bad), 0) AS fresh_bad,
                       coalesce(sum(fresh_planned), 0) AS fresh_planned, coalesce(sum(fresh_outside), 0) AS fresh_outside
                FROM availability_hours WHERE hour >= date_trunc('hour', CAST(:from AS timestamptz), 'UTC')
            )
            SELECT recent.measured + older.measured AS measured, recent.bad + older.bad AS bad, recent.planned + older.planned AS planned,
                   recent.outside + older.outside AS outside, recent.total + older.total AS total, recent.busy + older.busy AS busy,
                   recent.fresh_measured + older.fresh_measured AS fresh_measured, recent.fresh_bad + older.fresh_bad AS fresh_bad,
                   recent.fresh_planned + older.fresh_planned AS fresh_planned, recent.fresh_outside + older.fresh_outside AS fresh_outside
            FROM recent, older
            """.formatted(CLASSIFIED_MINUTES)), rules)
            .param("from", Timestamp.from(from))
            .query((rs, rowNum) -> new Counts(
                rs.getLong("measured"), rs.getLong("bad"), rs.getLong("planned"), rs.getLong("outside"), rs.getLong("total"), rs.getLong("busy"),
                rs.getLong("fresh_measured"), rs.getLong("fresh_bad"), rs.getLong("fresh_planned"), rs.getLong("fresh_outside")))
            .single();
    }

    private static JdbcClient.StatementSpec withRules(JdbcClient.StatementSpec statement, Rules rules) {
        return statement
            .param("goodPercent", rules.goodPercent())
            .param("noticeSeconds", rules.notice().toSeconds())
            .param("badSharePercent", rules.badSharePercent())
            .param("guaranteedTps", rules.guaranteedTps())
            .param("chaosAftermathSeconds", rules.chaosAftermath().toSeconds())
            .param("loadAftermathSeconds", rules.loadAftermath().toSeconds());
    }

    /** The first minute from {@code from} to {@code to}, both included, that monitoring has not told about yet. */
    public @Nullable Instant firstWithoutFreshness(Instant from, Instant to) {
        return jdbc.sql("SELECT min(minute) FROM availability_minutes WHERE transfers IS NULL AND minute >= :from AND minute <= :to")
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query(Timestamp.class)
            .optional()
            .map(Timestamp::toInstant)
            .orElse(null);
    }

    /** A minute monitoring has told about keeps what it told: another instance of control may have read it already. */
    public int fill(List<FreshMinute> minutes) {
        var filled = 0;
        for (var minute : minutes) {
            filled += jdbc.sql(
                "UPDATE availability_minutes SET transfers = :transfers, slow = :slow, restarted = :restarted WHERE minute = :minute AND transfers IS NULL")
                .param("minute", Timestamp.from(minute.minute()))
                .param("transfers", minute.transfers())
                .param("slow", minute.slow())
                .param("restarted", minute.restarted())
                .update();
        }
        return filled;
    }

    public int deleteMinutesBefore(Instant before) {
        return jdbc.sql("DELETE FROM availability_minutes WHERE minute < date_trunc('hour', CAST(:before AS timestamptz), 'UTC')")
            .param("before", Timestamp.from(before))
            .update();
    }

    public boolean inWindow(Instant now) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM maintenance_windows WHERE cancelled_at IS NULL AND starts_at <= :now AND :now < ends_at)")
            .param("now", Timestamp.from(now))
            .query(Boolean.class)
            .single();
    }

    public @Nullable MaintenanceWindow nextWindow(Instant now) {
        return jdbc.sql("SELECT starts_at, ends_at FROM maintenance_windows WHERE cancelled_at IS NULL AND ends_at > :now ORDER BY starts_at LIMIT 1")
            .param("now", Timestamp.from(now))
            .query((rs, rowNum) -> new MaintenanceWindow(rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()))
            .optional()
            .orElse(null);
    }
}
