package com.ledgerlab.ledger.persistence;

import com.ledgerlab.ledger.domain.Period;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Partitions are named after the start of their period, in UTC. The statements are built as text: DDL takes no
 * parameters, and every part comes from this class and the clock, never from a request.
 */
@Repository
public class PartitionRepository {

    private static final String MAINTENANCE_LOCK = "ledger.partition-maintenance";
    private static final String NAME_INFIX = "_p";
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
    public List<Instant> periodStarts(PartitionedTable table) {
        return jdbc.sql(
            """
            SELECT child.relname
            FROM pg_inherits i
            JOIN pg_class child ON child.oid = i.inhrelid
            JOIN pg_class parent ON parent.oid = i.inhparent
            JOIN pg_namespace n ON n.oid = parent.relnamespace
            WHERE parent.relname = :table AND n.nspname = current_schema()
            """)
            .param("table", table.tableName())
            .query(String.class)
            .list()
            .stream()
            .flatMap(name -> periodStart(table, name).stream())
            .sorted()
            .toList();
    }

    /**
     * Both take the strongest lock on the table: they wait for every transaction that uses it, and all that come after
     * wait behind them. So they give up after the timeout, and the next maintenance tries again.
     */
    public void create(PartitionedTable table, Period period, Duration lockTimeout) {
        limitLockWait(lockTimeout);
        jdbc.sql("CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
            .formatted(name(table, period.start()), table.tableName(), period.start(), period.end()))
            .update();
    }

    public void drop(PartitionedTable table, Instant periodStart, Duration lockTimeout) {
        limitLockWait(lockTimeout);
        jdbc.sql("DROP TABLE IF EXISTS " + name(table, periodStart)).update();
    }

    private void limitLockWait(Duration lockTimeout) {
        jdbc.sql("SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'").update();
    }

    public long rowsOutsidePartitions(PartitionedTable table) {
        return jdbc.sql("SELECT count(*) FROM " + defaultPartition(table)).query(Long.class).single();
    }

    public static String defaultPartition(PartitionedTable table) {
        return table.tableName() + "_default";
    }

    static String name(PartitionedTable table, Instant periodStart) {
        return table.tableName() + NAME_INFIX + NAME_TIME.format(periodStart);
    }

    private static Optional<Instant> periodStart(PartitionedTable table, String partitionName) {
        var prefix = table.tableName() + NAME_INFIX;
        if (!partitionName.startsWith(prefix)) {
            return Optional.empty();
        }
        return Optional.of(NAME_TIME.parse(partitionName.substring(prefix.length()), Instant::from));
    }
}
