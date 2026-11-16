package com.ledgerlab.control.availability;

import com.ledgerlab.control.generator.MinuteResponses;
import com.ledgerlab.control.periods.PeriodLoad;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AvailabilityRepository {

    /**
     * A minute is bad if fewer than :goodPercent of 100 requests got an answer, and planned if it lies in a window that
     * was not cancelled and was declared at least :noticeSeconds before its start. Its transfers count for the time to
     * posting once monitoring told them; a planned or outside minute takes all of them out, the fast ones too. It is
     * outside if it meets a crash or a pulse above :guaranteedTps, each with its aftermath, during which no service
     * restarted in place beyond what the event explains: a crash explains one start of each pod it crashed, a pulse none. A start counts up to :restartMarginSeconds after the event's end, and only where the pod
     * started before: a pod's first start is a new pod, not a restart. A minute meets an event if it starts before the
     * event's end and ends after its start. The events' windows are joined to the minutes once: a subquery per minute,
     * repeated in every count that reads it, cost the roll-up half a second of JIT compilation.
     */
    private static final String CLASSIFIED_MINUTES = """
        WITH event_windows AS (
            SELECT e.at AS starts, e.at + CAST(:chaosAftermathSeconds AS bigint) * interval '1 second' AS ends, e.instances AS crashed
            FROM chaos_events e
            UNION ALL
            SELECT p.at, p.until + CAST(:loadAftermathSeconds AS bigint) * interval '1 second', CAST('{}' AS text[])
            FROM load_pulses p WHERE p.tps > :guaranteedTps
        ), restarts AS (
            SELECT s.instance, s.started_at FROM service_starts s
            WHERE EXISTS (SELECT 1 FROM service_starts b WHERE b.instance = s.instance AND b.started_at < s.started_at)
        ), clean_windows AS (
            SELECT w.starts, w.ends FROM event_windows w
            WHERE NOT EXISTS (
                SELECT 1 FROM restarts r
                WHERE r.started_at > w.starts AND r.started_at <= w.ends + CAST(:restartMarginSeconds AS bigint) * interval '1 second'
                GROUP BY r.instance
                HAVING count(*) > CASE WHEN r.instance = ANY (w.crashed) THEN 1 ELSE 0 END)
        ), outside_minutes AS (
            SELECT DISTINCT o.minute FROM clean_windows w
            JOIN availability_minutes o ON o.minute > w.starts - interval '1 minute' AND o.minute < w.ends
        )
        SELECT m.minute, m.total, m.errors, m.busy,
               (m.total - m.errors) * 100 < m.total * :goodPercent AS bad,
               m.transfers, m.within_typical, m.within_tail,
               EXISTS (
                   SELECT 1 FROM maintenance_windows w
                   WHERE w.cancelled_at IS NULL AND w.starts_at <= m.minute AND m.minute < w.ends_at
                     AND w.declared_at <= w.starts_at - CAST(:noticeSeconds AS bigint) * interval '1 second') AS planned,
               o.minute IS NOT NULL AS outside
        FROM availability_minutes m LEFT JOIN outside_minutes o ON o.minute = m.minute""";

    /** A minute's posted transfers by how they count: judged, and of them within each threshold; planned; outside. */
    private static final String POSTED_SUMS = """
        coalesce(sum(c.transfers) FILTER (WHERE NOT c.planned AND NOT c.outside), 0) AS posted_judged,
        coalesce(sum(c.within_typical) FILTER (WHERE NOT c.planned AND NOT c.outside), 0) AS posted_within_typical,
        coalesce(sum(c.within_tail) FILTER (WHERE NOT c.planned AND NOT c.outside), 0) AS posted_within_tail,
        coalesce(sum(c.transfers) FILTER (WHERE c.planned), 0) AS posted_planned,
        coalesce(sum(c.transfers) FILTER (WHERE NOT c.planned AND c.outside), 0) AS posted_outside""";

    public record Rules(
        int goodPercent,
        Duration notice,
        int guaranteedTps,
        Duration chaosAftermath,
        Duration loadAftermath,
        Duration restartMargin
    ) {
    }

    /**
     * Bad minutes split three ways: {@code bad} use up the budget, {@code planned} lie in a maintenance window and
     * {@code outside} outside the bounds; a planned minute that is also outside counts as planned. Posted transfers split
     * the same way by their minute, the judged ones further by how many were within each threshold.
     * {@code postingSince} is the first hour with posted transfers counted, null before it.
     */
    public record Counts(
        long measuredMinutes,
        long badMinutes,
        long plannedMinutes,
        long outsideMinutes,
        long total,
        long busy,
        long postedJudged,
        long postedWithinTypical,
        long postedWithinTail,
        long postedPlanned,
        long postedOutside,
        @Nullable Instant postingSince
    ) {
    }

    public record PostedMinute(Instant minute, long transfers, long withinTypical, long withinTail) {
    }

    public record ServiceStart(String instance, Instant startedAt) {
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
                """
                INSERT INTO availability_minutes (minute, total, errors, busy, base_tps) VALUES (:minute, :total, :errors, :busy, :baseTps)
                ON CONFLICT (minute) DO NOTHING
                """)
                .param("minute", Timestamp.from(minute.minute()))
                .param("baseTps", minute.baseTps())
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
                                            posted_judged, posted_within_typical, posted_within_tail, posted_planned, posted_outside)
            SELECT date_trunc('hour', c.minute, 'UTC'), count(*),
                   count(*) FILTER (WHERE c.bad AND NOT c.planned AND NOT c.outside),
                   count(*) FILTER (WHERE c.bad AND c.planned),
                   count(*) FILTER (WHERE c.bad AND NOT c.planned AND c.outside),
                   sum(c.total), sum(c.errors), sum(c.busy),
                   %s
            FROM (%s) c
            WHERE c.minute >= date_trunc('hour', CAST(:keptFrom AS timestamptz), 'UTC') + interval '1 hour'
              AND c.minute < date_trunc('hour', CAST(:now AS timestamptz), 'UTC')
            GROUP BY 1
            ON CONFLICT (hour) DO UPDATE SET measured_minutes = EXCLUDED.measured_minutes, bad_minutes = EXCLUDED.bad_minutes,
                planned_minutes = EXCLUDED.planned_minutes, outside_minutes = EXCLUDED.outside_minutes, total = EXCLUDED.total,
                errors = EXCLUDED.errors, busy = EXCLUDED.busy, posted_judged = EXCLUDED.posted_judged,
                posted_within_typical = EXCLUDED.posted_within_typical, posted_within_tail = EXCLUDED.posted_within_tail,
                posted_planned = EXCLUDED.posted_planned, posted_outside = EXCLUDED.posted_outside
            """.formatted(POSTED_SUMS, CLASSIFIED_MINUTES)), rules)
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
                       %s,
                       date_trunc('hour', min(c.minute) FILTER (WHERE c.transfers > 0), 'UTC') AS posting_since
                FROM (%s) c
                WHERE c.minute >= (SELECT coalesce(max(hour) + interval '1 hour', CAST('-infinity' AS timestamptz)) FROM availability_hours)
                  AND c.minute >= date_trunc('hour', CAST(:from AS timestamptz), 'UTC')
            ), older AS (
                SELECT coalesce(sum(measured_minutes), 0) AS measured, coalesce(sum(bad_minutes), 0) AS bad,
                       coalesce(sum(planned_minutes), 0) AS planned, coalesce(sum(outside_minutes), 0) AS outside,
                       coalesce(sum(total), 0) AS total, coalesce(sum(busy), 0) AS busy,
                       coalesce(sum(posted_judged), 0) AS posted_judged, coalesce(sum(posted_within_typical), 0) AS posted_within_typical,
                       coalesce(sum(posted_within_tail), 0) AS posted_within_tail, coalesce(sum(posted_planned), 0) AS posted_planned,
                       coalesce(sum(posted_outside), 0) AS posted_outside,
                       min(hour) FILTER (WHERE posted_judged + posted_planned + posted_outside > 0) AS posting_since
                FROM availability_hours WHERE hour >= date_trunc('hour', CAST(:from AS timestamptz), 'UTC')
            )
            SELECT recent.measured + older.measured AS measured, recent.bad + older.bad AS bad, recent.planned + older.planned AS planned,
                   recent.outside + older.outside AS outside, recent.total + older.total AS total, recent.busy + older.busy AS busy,
                   recent.posted_judged + older.posted_judged AS posted_judged,
                   recent.posted_within_typical + older.posted_within_typical AS posted_within_typical,
                   recent.posted_within_tail + older.posted_within_tail AS posted_within_tail,
                   recent.posted_planned + older.posted_planned AS posted_planned, recent.posted_outside + older.posted_outside AS posted_outside,
                   least(older.posting_since, recent.posting_since) AS posting_since
            FROM recent, older
            """.formatted(POSTED_SUMS, CLASSIFIED_MINUTES)), rules)
            .param("from", Timestamp.from(from))
            .query((rs, rowNum) -> new Counts(
                rs.getLong("measured"), rs.getLong("bad"), rs.getLong("planned"), rs.getLong("outside"), rs.getLong("total"), rs.getLong("busy"),
                rs.getLong("posted_judged"), rs.getLong("posted_within_typical"), rs.getLong("posted_within_tail"),
                rs.getLong("posted_planned"), rs.getLong("posted_outside"), instantOrNull(rs.getTimestamp("posting_since"))))
            .single();
    }

    private static JdbcClient.StatementSpec withRules(JdbcClient.StatementSpec statement, Rules rules) {
        return statement
            .param("goodPercent", rules.goodPercent())
            .param("noticeSeconds", rules.notice().toSeconds())
            .param("guaranteedTps", rules.guaranteedTps())
            .param("chaosAftermathSeconds", rules.chaosAftermath().toSeconds())
            .param("loadAftermathSeconds", rules.loadAftermath().toSeconds())
            .param("restartMarginSeconds", rules.restartMargin().toSeconds());
    }

    private static @Nullable Instant instantOrNull(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** The first minute from {@code from} to {@code to}, both included, that monitoring has not told about yet. */
    public @Nullable Instant firstWithoutPosted(Instant from, Instant to) {
        return jdbc.sql("SELECT min(minute) FROM availability_minutes WHERE transfers IS NULL AND minute >= :from AND minute <= :to")
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query(Timestamp.class)
            .optional()
            .map(Timestamp::toInstant)
            .orElse(null);
    }

    /** A minute monitoring has told about keeps what it told: another instance of control may have read it already. */
    public int fill(List<PostedMinute> minutes) {
        var filled = 0;
        for (var minute : minutes) {
            filled += jdbc.sql(
                """
                UPDATE availability_minutes SET transfers = :transfers, within_typical = :withinTypical, within_tail = :withinTail
                WHERE minute = :minute AND transfers IS NULL
                """)
                .param("minute", Timestamp.from(minute.minute()))
                .param("transfers", minute.transfers())
                .param("withinTypical", minute.withinTypical())
                .param("withinTail", minute.withinTail())
                .update();
        }
        return filled;
    }

    /** Monitoring tells a pod's start every time it is read; each is kept once. */
    public int addStarts(Collection<ServiceStart> starts) {
        var added = 0;
        for (var start : starts) {
            added += jdbc.sql("INSERT INTO service_starts (instance, started_at) VALUES (:instance, :startedAt) ON CONFLICT DO NOTHING")
                .param("instance", start.instance())
                .param("startedAt", Timestamp.from(start.startedAt()))
                .update();
        }
        return added;
    }

    /** Each pod keeps its latest start before {@code before}, however old: a start after it is a restart only if one came before. */
    public int deleteStartsBefore(Instant before) {
        return jdbc.sql(
            """
            DELETE FROM service_starts s
            WHERE s.started_at < :before
              AND EXISTS (SELECT 1 FROM service_starts n WHERE n.instance = s.instance AND n.started_at > s.started_at AND n.started_at < :before)
            """)
            .param("before", Timestamp.from(before))
            .update();
    }

    public int deleteMinutesBefore(Instant before) {
        return jdbc.sql("DELETE FROM availability_minutes WHERE minute < date_trunc('hour', CAST(:before AS timestamptz), 'UTC')")
            .param("before", Timestamp.from(before))
            .update();
    }

    /** A pulse meets the period if it starts before the period's end and ends after its start. */
    public PeriodLoad loadBetween(Instant from, Instant to) {
        return jdbc.sql(
            """
            SELECT (SELECT min(base_tps) FROM availability_minutes WHERE minute >= :from AND minute < :to) AS min_base,
                   (SELECT max(base_tps) FROM availability_minutes WHERE minute >= :from AND minute < :to) AS max_base,
                   (SELECT max(tps) FROM load_pulses WHERE at < :to AND until > :from) AS max_pulse
            """)
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query((rs, rowNum) -> new PeriodLoad(
                rs.getObject("min_base", Integer.class), rs.getObject("max_base", Integer.class), rs.getObject("max_pulse", Integer.class)))
            .single();
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
