package com.ledgerlab.reporting.persistence;

import com.ledgerlab.reporting.domain.Period;
import com.ledgerlab.reporting.domain.PeriodStats;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SecondStatsRepository {

    private final JdbcClient jdbc;

    public SecondStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final class Sums {
        private long accepted;
        private long commandsSeen;
        private long posted;
        private long rejected;
        private long postedAmountCents;

        void add(StatsChange change) {
            accepted += change.accepted() ? 1 : 0;
            commandsSeen += change.commandSeen() ? 1 : 0;
            posted += change.posted() ? 1 : 0;
            rejected += change.rejected() ? 1 : 0;
            postedAmountCents += change.postedAmountCents();
        }
    }

    private record Rejection(Instant second, String reason) implements Comparable<Rejection> {
        @Override
        public int compareTo(Rejection other) {
            var bySecond = second.compareTo(other.second);
            return bySecond != 0 ? bySecond : reason.compareTo(other.reason);
        }
    }

    /**
     * In the transaction that wrote the transfers' rows, so the numbers change with them or not at all. Seconds and
     * reasons go in one order in every batch: the consumers add to the same seconds at once.
     */
    public void add(List<StatsChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        var seconds = new TreeMap<Instant, Sums>();
        var rejections = new TreeMap<Rejection, Long>();
        for (var change : changes) {
            var second = change.acceptedAt().truncatedTo(ChronoUnit.SECONDS);
            seconds.computeIfAbsent(second, it -> new Sums()).add(change);
            if (change.rejected() && change.rejectReason() != null) {
                rejections.merge(new Rejection(second, change.rejectReason()), 1L, Long::sum);
            }
        }
        jdbc.sql(
            """
            INSERT INTO second_stats AS s (second, accepted, commands_seen, posted, rejected, posted_amount_cents)
            SELECT * FROM unnest(
                CAST(:seconds AS timestamptz[]), CAST(:accepted AS bigint[]), CAST(:commandsSeen AS bigint[]), CAST(:posted AS bigint[]),
                CAST(:rejected AS bigint[]), CAST(:postedAmounts AS bigint[]))
            ON CONFLICT (second) DO UPDATE SET
                accepted = s.accepted + EXCLUDED.accepted,
                commands_seen = s.commands_seen + EXCLUDED.commands_seen,
                posted = s.posted + EXCLUDED.posted,
                rejected = s.rejected + EXCLUDED.rejected,
                posted_amount_cents = s.posted_amount_cents + EXCLUDED.posted_amount_cents
            """)
            .param("seconds", Sql.timestamptzArray(seconds.keySet()))
            .param("accepted", column(seconds.values(), it -> it.accepted))
            .param("commandsSeen", column(seconds.values(), it -> it.commandsSeen))
            .param("posted", column(seconds.values(), it -> it.posted))
            .param("rejected", column(seconds.values(), it -> it.rejected))
            .param("postedAmounts", column(seconds.values(), it -> it.postedAmountCents))
            .update();
        if (rejections.isEmpty()) {
            return;
        }
        var reasons = new ArrayList<>(rejections.keySet());
        jdbc.sql(
            """
            INSERT INTO second_rejections AS r (second, reject_reason, rejected)
            SELECT * FROM unnest(CAST(:seconds AS timestamptz[]), CAST(:reasons AS text[]), CAST(:rejected AS bigint[]))
            ON CONFLICT (second, reject_reason) DO UPDATE SET rejected = r.rejected + EXCLUDED.rejected
            """)
            .param("seconds", Sql.timestamptzArray(reasons.stream().map(Rejection::second).toList()))
            .param("reasons", reasons.stream().map(Rejection::reason).toArray(String[]::new))
            .param("rejected", rejections.values().toArray(Long[]::new))
            .update();
    }

    private static Long[] column(Collection<Sums> seconds, Function<Sums, Long> sum) {
        return seconds.stream().map(sum).toArray(Long[]::new);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PeriodStats sum(Period period) {
        var reasons = new LinkedHashMap<String, Long>();
        jdbc.sql(
            """
            SELECT reject_reason, sum(rejected) AS rejected FROM second_rejections
            WHERE second >= :start AND second < :end
            GROUP BY reject_reason ORDER BY reject_reason
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .query(rs -> {
                reasons.put(rs.getString("reject_reason"), rs.getLong("rejected"));
            });
        return jdbc.sql(
            """
            SELECT now() AS at,
                   coalesce(sum(accepted), 0) AS accepted,
                   coalesce(sum(commands_seen), 0) AS commands_seen,
                   coalesce(sum(posted), 0) AS posted,
                   coalesce(sum(rejected), 0) AS rejected,
                   coalesce(sum(posted_amount_cents), 0) AS posted_amount_cents
            FROM second_stats WHERE second >= :start AND second < :end
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .query((rs, rowNum) -> new PeriodStats(
                period.start(),
                Sql.instant(rs, "at"),
                rs.getLong("accepted"),
                rs.getLong("commands_seen"),
                rs.getLong("posted"),
                rs.getLong("rejected"),
                rs.getLong("posted_amount_cents"),
                reasons))
            .single();
    }

    public void deleteBefore(Instant periodStart) {
        jdbc.sql("DELETE FROM second_stats WHERE second < :start").param("start", Sql.timestamptz(periodStart)).update();
        jdbc.sql("DELETE FROM second_rejections WHERE second < :start").param("start", Sql.timestamptz(periodStart)).update();
    }
}
