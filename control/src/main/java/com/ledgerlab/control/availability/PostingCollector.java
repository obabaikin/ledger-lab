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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the kept minutes how many transfers were posted within each threshold, from the gateway's histogram in
 * Prometheus, and keeps when the services' pods started. The minutes come from a range query at a step of a minute: its
 * value at an instant is the minute before it.
 */
@Component
public class PostingCollector {

    private static final Logger log = LoggerFactory.getLogger(PostingCollector.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);
    /**
     * Shorter than a JVM takes to start and be scraped, so no window holds two new starts of one pod and each is read;
     * the windows tile the time without a gap, as the step is the window.
     */
    private static final Duration STARTS_STEP = Duration.ofSeconds(2);
    private static final String STARTS_QUERY = BankMetrics.PROCESS_STARTS.formatted(BankMetrics.window(STARTS_STEP));
    private static final String INSTANCE = "instance";
    private static final double MILLIS_PER_SECOND = 1000;

    private final AvailabilityRepository minutes;
    private final PrometheusClient prometheus;
    private final ControlProperties.Posting props;
    private final String transfersQuery;
    private final String typicalQuery;
    private final String tailQuery;
    private volatile boolean lastAnswered = true;
    private volatile @Nullable Instant startsReadUpTo;

    public PostingCollector(AvailabilityRepository minutes, PrometheusClient prometheus, ControlProperties props) {
        this.minutes = minutes;
        this.prometheus = prometheus;
        this.props = props.posting();
        this.transfersQuery = BankMetrics.transfersInMinute(this.props.targets().stream().map(ControlProperties.Target::threshold).toList());
        this.typicalQuery = BankMetrics.WITHIN_IN_MINUTE.formatted(BankMetrics.bucketBound(this.props.typical().threshold()));
        this.tailQuery = BankMetrics.WITHIN_IN_MINUTE.formatted(BankMetrics.bucketBound(this.props.tail().threshold()));
    }

    public int collect(Instant now) throws InterruptedException {
        var startsRead = readStarts(now);
        var told = readMinutes(now);
        var answered = startsRead && told != null;
        if (answered != lastAnswered) {
            log.warn("Posted transfers of the minutes read from Prometheus: {}; minutes up to {} old are read once it answers again", answered, props.catchUp());
        }
        lastAnswered = answered;
        return told == null ? 0 : told;
    }

    private boolean readStarts(Instant now) throws InterruptedException {
        var readUpTo = startsReadUpTo;
        var oldest = now.minus(props.catchUp());
        var from = readUpTo == null || readUpTo.isBefore(oldest) ? oldest : readUpTo.plus(STARTS_STEP);
        if (from.isAfter(now)) {
            return true;
        }
        var to = from.plus(STARTS_STEP.multipliedBy(Duration.between(from, now).dividedBy(STARTS_STEP)));
        var ranges = prometheus.queryRange(STARTS_QUERY, from, to, STARTS_STEP);
        if (ranges == null) {
            return false;
        }
        var starts = new HashSet<AvailabilityRepository.ServiceStart>();
        for (var range : ranges) {
            var instance = range.metric().get(INSTANCE);
            if (instance == null) {
                continue;
            }
            for (var sample : range.values()) {
                var seconds = Double.parseDouble(sample.get(1).toString());
                if (Double.isFinite(seconds)) {
                    starts.add(new AvailabilityRepository.ServiceStart(instance, Instant.ofEpochMilli(Math.round(seconds * MILLIS_PER_SECOND))));
                }
            }
        }
        minutes.addStarts(starts);
        startsReadUpTo = to;
        return true;
    }

    /** The number of minutes told, or null if Prometheus did not answer. */
    private @Nullable Integer readMinutes(Instant now) throws InterruptedException {
        var last = now.minus(props.lag()).truncatedTo(ChronoUnit.MINUTES).minus(MINUTE);
        var first = minutes.firstWithoutPosted(now.minus(props.catchUp()), last);
        if (first == null) {
            return 0;
        }
        var start = first.plus(MINUTE);
        var end = last.plus(MINUTE);
        var transfers = prometheus.queryRange(transfersQuery, start, end, MINUTE);
        var typical = transfers == null ? null : prometheus.queryRange(typicalQuery, start, end, MINUTE);
        var tail = typical == null ? null : prometheus.queryRange(tailQuery, start, end, MINUTE);
        if (transfers == null || typical == null || tail == null) {
            return null;
        }
        var typicalByMinute = byMinute(typical);
        var tailByMinute = byMinute(tail);
        var told = new ArrayList<AvailabilityRepository.PostedMinute>();
        for (var entry : byMinute(transfers).entrySet()) {
            var typicalCount = typicalByMinute.get(entry.getKey());
            var tailCount = tailByMinute.get(entry.getKey());
            if (typicalCount == null || tailCount == null) {
                continue;
            }
            var count = Math.round(entry.getValue());
            // Increases are extrapolated per series: a bucket may come out a little above the count it is part of.
            told.add(new AvailabilityRepository.PostedMinute(
                entry.getKey(), count, Math.min(count, Math.round(typicalCount)), Math.min(count, Math.round(tailCount))));
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
