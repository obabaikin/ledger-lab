package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.persistence.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the idempotency keys older than the window (ADR 0022); until then such a key is only ignored. */
@Component
public class ExpiredKeys {

    public static final String CLEANUP_METRIC = "gateway.keys.cleanup";
    public static final String DELETED_METRIC = "gateway.keys.deleted";

    private static final Logger log = LoggerFactory.getLogger(ExpiredKeys.class);

    private final TransferRepository transfers;
    private final GatewayProperties.Periods props;
    private final Clock clock;
    private final Timer cleanup;
    private final Counter deleted;

    public ExpiredKeys(TransferRepository transfers, GatewayProperties props, Clock clock, MeterRegistry registry) {
        this.transfers = transfers;
        this.props = props.periods();
        this.clock = clock;
        this.cleanup = Timer.builder(CLEANUP_METRIC)
            .description("Time to delete the idempotency keys that left the window")
            .register(registry);
        this.deleted = Counter.builder(DELETED_METRIC)
            .description("Idempotency keys deleted after they left the window")
            .register(registry);
    }

    @Scheduled(fixedDelayString = "${gateway.periods.key-cleanup-interval}", initialDelayString = "${gateway.periods.key-cleanup-first-delay}")
    public void deleteNow() {
        var deleted = delete(clock.instant());
        log.debug("Deleted {} idempotency keys older than {}", deleted, props.retention());
    }

    public long delete(Instant now) {
        var started = Timer.start();
        var keysSince = now.minus(props.retention());
        var total = 0L;
        int inStatement;
        do {
            inStatement = transfers.deleteKeysBefore(keysSince, props.keyCleanupBatch());
            total += inStatement;
        } while (inStatement == props.keyCleanupBatch());
        started.stop(cleanup);
        deleted.increment((double) total);
        return total;
    }
}
