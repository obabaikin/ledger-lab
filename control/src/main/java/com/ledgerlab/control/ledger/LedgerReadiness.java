package com.ledgerlab.control.ledger;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.metrics.LedgerMetrics;
import com.ledgerlab.control.metrics.PrometheusClient;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** A failed scrape makes an instance's series stale, so the sum counts only instances that answered (ADR 0012). */
@Component
public class LedgerReadiness {

    private static final String HELD_PARTITIONS = "sum(" + LedgerMetrics.ASSIGNED_PARTITIONS + ")";

    private static final Logger log = LoggerFactory.getLogger(LedgerReadiness.class);

    private final PrometheusClient prometheus;
    private final int partitions;
    private volatile @Nullable Integer lastHeld = null;

    public LedgerReadiness(PrometheusClient prometheus, ControlProperties props) {
        this.prometheus = prometheus;
        this.partitions = props.ledger().transferPartitions();
    }

    public boolean isReady() throws InterruptedException {
        var samples = prometheus.query(HELD_PARTITIONS);
        Integer held = samples != null && samples.size() == 1 ? (int) Math.round(samples.getFirst().number()) : null;
        if (!Objects.equals(held, lastHeld)) {
            log.info("Ledger instances hold {} of {} partitions; runs start only at all of them", held, partitions);
        }
        lastHeld = held;
        return held != null && held == partitions;
    }
}
