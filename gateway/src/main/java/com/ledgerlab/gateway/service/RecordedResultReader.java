package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.persistence.TransferKey;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The event wakes a request before the recorder's commit shows in the row, so the request reads until it does.
 * It never writes the result itself: a second writer of the same row waited for the recorder's batch, and the batch for it.
 */
@Component
public class RecordedResultReader {

    private static final long PAUSE_GROWTH = 2L;
    private static final double[] READS_PERCENTILES = {0.5, 0.99};

    private final TransferService service;
    private final Duration firstPause;
    private final int maxReads;
    private final DistributionSummary reads;

    public RecordedResultReader(TransferService service, GatewayProperties props, MeterRegistry registry) {
        this.service = service;
        this.firstPause = props.waitRead().firstPause();
        this.maxReads = props.waitRead().maxReads();
        this.reads = DistributionSummary.builder("gateway.wait.reads")
            .description("reads a woken request made until it saw a final status, ran out of reads or its wait ended")
            .publishPercentiles(READS_PERCENTILES)
            .register(registry);
    }

    /** The row once it is final, or as it stands after the last read or when {@code deadlineNanos} (of {@link System#nanoTime}) passes. */
    public Transfer readFinal(Transfer accepted, long deadlineNanos) throws InterruptedException {
        var key = TransferKey.of(accepted);
        var pause = firstPause;
        var count = 0;
        while (true) {
            var left = Duration.ofNanos(Math.max(deadlineNanos - System.nanoTime(), 0));
            Thread.sleep(pause.compareTo(left) <= 0 ? pause : left);
            var found = service.find(key);
            var row = found != null ? found : accepted;
            count++;
            if (found == null || row.status().isFinal() || count == maxReads || System.nanoTime() >= deadlineNanos) {
                reads.record(count);
                return row;
            }
            pause = pause.multipliedBy(PAUSE_GROWTH);
        }
    }
}
