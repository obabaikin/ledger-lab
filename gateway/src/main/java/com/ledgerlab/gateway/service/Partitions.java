package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.domain.Period;
import com.ledgerlab.gateway.persistence.PartitionRepository;
import com.ledgerlab.gateway.persistence.PeriodRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
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
 * than the retention together with their periods' closing rows (ADR 0022). Before the web server starts, so the first
 * transfer finds its partition.
 */
@Component
@DependsOnDatabaseInitialization
public class Partitions implements InitializingBean {

    public static final String AHEAD_METRIC = "gateway.partitions.ahead";
    public static final String UNPARTITIONED_ROWS_METRIC = "gateway.partitions.default.rows";
    public static final String FAILURES_METRIC = "gateway.partitions.failures";

    private static final Logger log = LoggerFactory.getLogger(Partitions.class);

    private final PartitionRepository partitions;
    private final PeriodRepository periods;
    private final GatewayProperties.Periods props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Counter failures;
    private final AtomicLong ahead = new AtomicLong();
    private final AtomicLong unpartitionedRows = new AtomicLong();

    public Partitions(
        PartitionRepository partitions,
        PeriodRepository periods,
        GatewayProperties props,
        Clock clock,
        TransactionTemplate tx,
        MeterRegistry registry
    ) {
        this.partitions = partitions;
        this.periods = periods;
        this.props = props.periods();
        this.clock = clock;
        this.tx = tx;
        this.failures = Counter.builder(FAILURES_METRIC)
            .description("Partitions that could not be created or dropped; tried again at the next maintenance")
            .register(registry);
        Gauge.builder(AHEAD_METRIC, ahead, AtomicLong::get)
            .description("Partitions for periods that have not begun; at 0 new transfers go to the default partition")
            .register(registry);
        Gauge.builder(UNPARTITIONED_ROWS_METRIC, unpartitionedRows, AtomicLong::get)
            .description("Transfers in the default partition: no partition covered their time")
            .register(registry);
    }

    @Override
    public void afterPropertiesSet() {
        requireOneLength();
        maintain(clock.instant());
    }

    /** A partition cut for a longer period would be dropped by this length's arithmetic before its rows are old enough. */
    private void requireOneLength() {
        var others = partitions.partitionsNotOf(props.length());
        if (!others.isEmpty()) {
            throw new IllegalStateException("Partitions %s were cut for another period length than %s; a changed length needs an empty database"
                .formatted(others, props.length()));
        }
    }

    @Scheduled(fixedDelayString = "${gateway.periods.maintenance-interval}", initialDelayString = "${gateway.periods.maintenance-interval}")
    public void maintainNow() {
        maintain(clock.instant());
    }

    public void maintain(Instant now) {
        createMissing(now);
        dropExpired(now);
        measure(now);
    }

    public void measure(Instant now) {
        ahead.set(partitions.periodStarts().stream().filter(start -> start.isAfter(now)).count());
        unpartitionedRows.set(partitions.rowsOutsidePartitions());
    }

    private void createMissing(Instant now) {
        var existing = partitions.periodStarts();
        var last = now.plus(props.partitionsAhead());
        for (var period = Period.containing(now.minus(props.retention()), props.length()); !period.start().isAfter(last); period = period.next()) {
            if (!existing.contains(period.start())) {
                var missing = period;
                // Each in its own transaction: one that cannot be created leaves the others in place.
                attempt("create", missing.start(), () -> partitions.create(missing, props.lockTimeout()));
            }
        }
    }

    /** A partition goes once its whole period is older than the retention: its last row was held that long. */
    private void dropExpired(Instant now) {
        var horizon = now.minus(props.retention());
        for (var start : partitions.periodStarts()) {
            if (!start.plus(props.length()).isAfter(horizon)) {
                attempt("drop", start, () -> partitions.drop(start, props.lockTimeout()));
            }
        }
        periods.deleteBefore(Period.containing(horizon, props.length()).start());
    }

    private void attempt(String action, Instant periodStart, Runnable change) {
        try {
            tx.executeWithoutResult(status -> {
                partitions.lockMaintenance();
                change.run();
            });
        } catch (DataAccessException e) {
            failures.increment();
            log.warn("Could not {} the partition of {} for the period from {}", action, PartitionRepository.TABLE, periodStart, e);
        }
    }
}
