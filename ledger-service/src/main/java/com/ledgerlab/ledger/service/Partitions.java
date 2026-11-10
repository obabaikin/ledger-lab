package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.domain.Period;
import com.ledgerlab.ledger.persistence.PartitionRepository;
import com.ledgerlab.ledger.persistence.PartitionedTable;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps a partition per period from the oldest one still held to the configured time ahead, and drops the ones older
 * than the retention (ADR 0022). Before the consumers start, so the first transfer finds its partition.
 */
@Component
@DependsOnDatabaseInitialization
public class Partitions implements InitializingBean {

    public static final String AHEAD_METRIC = "ledger.partitions.ahead";
    public static final String UNPARTITIONED_ROWS_METRIC = "ledger.partitions.default.rows";
    public static final String FAILURES_METRIC = "ledger.partitions.failures";
    public static final String TABLE_TAG = "table";

    private static final Logger log = LoggerFactory.getLogger(Partitions.class);

    private final PartitionRepository partitions;
    private final PeriodsProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Counter failures;
    private final Map<PartitionedTable, AtomicLong> ahead = new EnumMap<>(PartitionedTable.class);
    private final Map<PartitionedTable, AtomicLong> unpartitionedRows = new EnumMap<>(PartitionedTable.class);

    public Partitions(PartitionRepository partitions, PeriodsProperties props, Clock clock, TransactionTemplate tx, MeterRegistry registry) {
        this.partitions = partitions;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
        this.failures = Counter.builder(FAILURES_METRIC)
            .description("Partitions that could not be created or dropped; tried again at the next maintenance")
            .register(registry);
        for (var table : PartitionedTable.values()) {
            var partitionsAhead = new AtomicLong();
            var rowsInDefault = new AtomicLong();
            ahead.put(table, partitionsAhead);
            unpartitionedRows.put(table, rowsInDefault);
            Gauge.builder(AHEAD_METRIC, partitionsAhead, AtomicLong::get)
                .description("Partitions for periods that have not begun; at 0 new rows go to the default partition")
                .tag(TABLE_TAG, table.tableName())
                .register(registry);
            Gauge.builder(UNPARTITIONED_ROWS_METRIC, rowsInDefault, AtomicLong::get)
                .description("Rows in the default partition: no partition covered their time")
                .tag(TABLE_TAG, table.tableName())
                .register(registry);
        }
    }

    @Override
    public void afterPropertiesSet() {
        maintain(clock.instant());
    }

    @Scheduled(fixedDelayString = "${ledger.periods.maintenance-interval}", initialDelayString = "${ledger.periods.maintenance-interval}")
    public void maintainNow() {
        maintain(clock.instant());
    }

    public void maintain(Instant now) {
        for (var table : PartitionedTable.values()) {
            createMissing(table, now);
            dropExpired(table, now);
        }
        measure(now);
    }

    public void measure(Instant now) {
        for (var table : PartitionedTable.values()) {
            gauge(ahead, table).set(partitions.periodStarts(table).stream().filter(start -> start.isAfter(now)).count());
            gauge(unpartitionedRows, table).set(partitions.rowsOutsidePartitions(table));
        }
    }

    private static AtomicLong gauge(Map<PartitionedTable, AtomicLong> gauges, PartitionedTable table) {
        return Objects.requireNonNull(gauges.get(table), "a gauge per table");
    }

    private void createMissing(PartitionedTable table, Instant now) {
        var existing = partitions.periodStarts(table);
        var last = now.plus(props.partitionsAhead());
        for (var period = Period.containing(now.minus(props.retention()), props.length()); !period.start().isAfter(last); period = period.next()) {
            if (!existing.contains(period.start())) {
                var missing = period;
                // Each in its own transaction: one that cannot be created leaves the others in place.
                attempt("create", table, missing.start(), () -> partitions.create(table, missing));
            }
        }
    }

    /** A partition goes once its whole period is older than the retention: its last row was held that long. */
    private void dropExpired(PartitionedTable table, Instant now) {
        var horizon = now.minus(props.retention());
        for (var start : partitions.periodStarts(table)) {
            if (!start.plus(props.length()).isAfter(horizon)) {
                attempt("drop", table, start, () -> partitions.drop(table, start, props.dropLockTimeout()));
            }
        }
    }

    private void attempt(String action, PartitionedTable table, Instant periodStart, Runnable change) {
        try {
            tx.executeWithoutResult(status -> {
                partitions.lockMaintenance();
                change.run();
            });
        } catch (DataAccessException e) {
            failures.increment();
            log.warn("Could not {} the partition of {} for the period from {}", action, table.tableName(), periodStart, e);
        }
    }
}
