package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.persistence.TransferRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the idempotency keys older than the window (ADR 0022); until then such a key is only ignored. */
@Component
public class ExpiredKeys {

    private static final Logger log = LoggerFactory.getLogger(ExpiredKeys.class);

    private final TransferRepository transfers;
    private final GatewayProperties.Periods props;
    private final Clock clock;

    public ExpiredKeys(TransferRepository transfers, GatewayProperties props, Clock clock) {
        this.transfers = transfers;
        this.props = props.periods();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gateway.periods.key-cleanup-interval}", initialDelayString = "${gateway.periods.key-cleanup-first-delay}")
    public void deleteNow() {
        var deleted = delete(clock.instant());
        log.debug("Deleted {} idempotency keys older than {}", deleted, props.retention());
    }

    public long delete(Instant now) {
        var keysSince = now.minus(props.retention());
        var total = 0L;
        int deleted;
        do {
            deleted = transfers.deleteKeysBefore(keysSince, props.keyCleanupBatch());
            total += deleted;
        } while (deleted == props.keyCleanupBatch());
        return total;
    }
}
