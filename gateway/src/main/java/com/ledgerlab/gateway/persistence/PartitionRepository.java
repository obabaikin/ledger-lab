package com.ledgerlab.gateway.persistence;

import com.ledgerlab.gateway.domain.Period;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Partitions of the transfers are named after the start of their period, in UTC. The statements are built as text:
 * DDL takes no parameters, and every part comes from this class and the clock, never from a request.
 */
@Repository
public class PartitionRepository {

    public static final String TABLE = "transfers";
    public static final String DEFAULT_PARTITION = TABLE + "_default";

    private static final String MAINTENANCE_LOCK = "gateway.partition-maintenance";
    private static final String NAME_PREFIX = TABLE + "_p";
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public PartitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Held until the transaction ends: one instance changes the partitions at a time. */
    public void lockMaintenance() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))").param("name", MAINTENANCE_LOCK).query(rs -> { });
    }

    /** Starts of the periods that have a partition; the default partition is not among them. */
    public List<Instant> periodStarts() {
        return jdbc.sql(
            """
            SELECT child.relname
            FROM pg_inherits i
            JOIN pg_class child ON child.oid = i.inhrelid
            JOIN pg_class parent ON parent.oid = i.inhparent
            JOIN pg_namespace n ON n.oid = parent.relnamespace
            WHERE parent.relname = :table AND n.nspname = current_schema()
            """)
            .param("table", TABLE)
            .query(String.class)
            .list()
            .stream()
            .flatMap(name -> periodStart(name).stream())
            .sorted()
            .toList();
    }

    /** Names of the partitions whose bounds, as Postgres keeps them, are not one period of this length. */
    public List<String> partitionsNotOf(Duration length) {
        return jdbc.sql(
            """
            SELECT child.relname
            FROM pg_inherits i
            JOIN pg_class child ON child.oid = i.inhrelid
            JOIN pg_class parent ON parent.oid = i.inhparent
            JOIN pg_namespace n ON n.oid = parent.relnamespace
            CROSS JOIN LATERAL regexp_match(pg_get_expr(child.relpartbound, child.oid),
                                            'FROM \\(''([^'']+)''\\) TO \\(''([^'']+)''\\)') AS bound(b)
            WHERE parent.relname = :table AND n.nspname = current_schema()
              AND bound.b IS NOT NULL
              AND CAST(bound.b[2] AS timestamptz) - CAST(bound.b[1] AS timestamptz) <> make_interval(secs => :seconds)
            ORDER BY child.relname
            """)
            .param("table", TABLE)
            .param("seconds", length.toSeconds())
            .query(String.class)
            .list();
    }

    /**
     * Both take the strongest lock on the table: they wait for every transaction that uses it, and all that come after
     * wait behind them. So they give up after the timeout, and the next maintenance tries again.
     */
    public void create(Period period, Duration lockTimeout) {
        limitLockWait(lockTimeout);
        jdbc.sql("CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
            .formatted(name(period.start()), TABLE, period.start(), period.end()))
            .update();
    }

    public void drop(Instant periodStart, Duration lockTimeout) {
        limitLockWait(lockTimeout);
        jdbc.sql("DROP TABLE IF EXISTS " + name(periodStart)).update();
    }

    private void limitLockWait(Duration lockTimeout) {
        jdbc.sql("SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'").update();
    }

    /** A period whose partition was missing (dropped by hand, or not created in time) kept its rows here past it. */
    public int deleteUnpartitionedBefore(Instant periodStart, int limit) {
        return jdbc.sql("DELETE FROM %1$s WHERE ctid IN (SELECT ctid FROM %1$s WHERE accepted_at < :start LIMIT :limit)".formatted(DEFAULT_PARTITION))
            .param("start", Sql.timestamptz(periodStart))
            .param("limit", limit)
            .update();
    }

    public long rowsOutsidePartitions() {
        return jdbc.sql("SELECT count(*) FROM " + DEFAULT_PARTITION).query(Long.class).single();
    }

    static String name(Instant periodStart) {
        return NAME_PREFIX + NAME_TIME.format(periodStart);
    }

    private static Optional<Instant> periodStart(String partitionName) {
        if (!partitionName.startsWith(NAME_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(NAME_TIME.parse(partitionName.substring(NAME_PREFIX.length()), Instant::from));
    }
}
