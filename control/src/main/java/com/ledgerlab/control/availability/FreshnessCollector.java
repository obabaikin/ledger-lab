package com.ledgerlab.control.availability;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.metrics.BankMetrics;
import com.ledgerlab.control.metrics.PrometheusClient;
import com.ledgerlab.control.metrics.PrometheusRange;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the kept minutes how fast their transfers were and whether a service restarted in place, from the gateway's
 * histogram in Prometheus. A range query at a step of a minute: its value at an instant is the minute before it.
 */
@Component
public class FreshnessCollector {

    private static final Logger log = LoggerFactory.getLogger(FreshnessCollector.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final AvailabilityRepository minutes;
    private final PrometheusClient prometheus;
    private final ControlProperties.Freshness props;
    private final String fastQuery;
    private volatile boolean lastAnswered = true;

    public FreshnessCollector(AvailabilityRepository minutes, PrometheusClient prometheus, ControlProperties props) {
        this.minutes = minutes;
        this.prometheus = prometheus;
        this.props = props.freshness();
        this.fastQuery = BankMetrics.FAST_IN_MINUTE.formatted(BankMetrics.bucketBound(this.props.threshold()));
    }

    public int collect(Instant now) throws InterruptedException {
        var last = now.minus(props.lag()).truncatedTo(ChronoUnit.MINUTES).minus(MINUTE);
        var first = minutes.firstWithoutFreshness(now.minus(props.catchUp()), last);
        if (first == null) {
            return 0;
        }
        var start = first.plus(MINUTE);
        var end = last.plus(MINUTE);
        var transfers = prometheus.queryRange(BankMetrics.TRANSFERS_IN_MINUTE, start, end, MINUTE);
        var fast = transfers == null ? null : prometheus.queryRange(fastQuery, start, end, MINUTE);
        var restarts = fast == null ? null : prometheus.queryRange(BankMetrics.RESTARTED_IN_MINUTE, start, end, MINUTE);
        var answered = restarts != null;
        if (answered != lastAnswered) {
            log.warn("Freshness of the minutes read from Prometheus: {}; minutes up to {} old are read once it answers again", answered, props.catchUp());
        }
        lastAnswered = answered;
        if (transfers == null || fast == null || restarts == null) {
            return 0;
        }
        var fastByMinute = byMinute(fast);
        var restartsByMinute = byMinute(restarts);
        var told = new ArrayList<AvailabilityRepository.FreshMinute>();
        for (var entry : byMinute(transfers).entrySet()) {
            var fastCount = fastByMinute.get(entry.getKey());
            if (fastCount == null) {
                continue;
            }
            var count = Math.round(entry.getValue());
            var restarted = restartsByMinute.get(entry.getKey());
            told.add(new AvailabilityRepository.FreshMinute(
                entry.getKey(), count, Math.max(0, count - Math.round(fastCount)), restarted == null ? null : restarted > 0));
        }
        return minutes.fill(told);
    }

    /** By the minute each value tells about; a query adds its series up to one, so there is one at most. */
    private static Map<Instant, Double> byMinute(List<PrometheusRange> ranges) {
        var values = new HashMap<Instant, Double>();
        for (var sample : ranges.isEmpty() ? List.<List<Object>>of() : ranges.getFirst().values()) {
            var value = Double.parseDouble(sample.get(1).toString());
            if (Double.isFinite(value)) {
                values.put(Instant.ofEpochSecond(Math.round(Double.parseDouble(sample.get(0).toString()))).minus(MINUTE), value);
            }
        }
        return values;
    }
}
