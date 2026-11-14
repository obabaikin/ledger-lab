package com.ledgerlab.control.bank;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.metrics.PrometheusClient;
import com.ledgerlab.control.metrics.PrometheusRange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

/** Prometheus is asked once for all visitors: the answer is kept for a few seconds. */
@Service
public class SeriesService {

    private record Kept(SeriesView view, long readAtNanos) {
    }

    private final PrometheusClient prometheus;
    private final ControlProperties.Series props;
    private final Map<Duration, Kept> kept = new HashMap<>();

    @Autowired
    public SeriesService(PrometheusClient prometheus, ControlProperties props) {
        this(prometheus, props.series());
    }

    public SeriesService(PrometheusClient prometheus, ControlProperties.Series props) {
        this.prometheus = prometheus;
        this.props = props;
    }

    public synchronized SeriesView series(@Nullable String range) throws InterruptedException, ExecutionException {
        var wanted = range == null ? props.defaultRange() : parsed(range);
        var served = props.ranges().stream().filter(it -> it.range().equals(wanted)).findFirst().orElseThrow(InvalidRangeException::new);
        var last = kept.get(served.range());
        if (last != null && System.nanoTime() - last.readAtNanos() < props.cache().toNanos()) {
            return last.view();
        }
        var view = read(served, Instant.now());
        kept.put(served.range(), new Kept(view, System.nanoTime()));
        return view;
    }

    private static @Nullable Duration parsed(String range) {
        try {
            return DurationStyle.SIMPLE.parse(range);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private SeriesView read(ControlProperties.Range range, Instant now) throws InterruptedException, ExecutionException {
        var stepSeconds = range.step().toSeconds();
        var end = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), stepSeconds) * stepSeconds);
        var start = end.minus(range.range());
        var points = Math.toIntExact(range.range().toSeconds() / stepSeconds) + 1;
        var series = new ArrayList<SeriesView.Series>();
        try (var reads = Executors.newVirtualThreadPerTaskExecutor()) {
            var answers = new LinkedHashMap<SeriesName, Future<@Nullable List<PrometheusRange>>>();
            for (var name : SeriesName.values()) {
                answers.put(name, reads.submit(() -> prometheus.queryRange(name.query(range.window()), start, end, range.step())));
            }
            for (var answer : answers.entrySet()) {
                var ranges = answer.getValue().get();
                if (ranges != null) {
                    series.add(new SeriesView.Series(answer.getKey(), onGrid(ranges, start, stepSeconds, points)));
                }
            }
        }
        return new SeriesView(start, stepSeconds, points, series);
    }

    private static List<@Nullable Double> onGrid(List<PrometheusRange> ranges, Instant start, long stepSeconds, int points) {
        var values = new @Nullable Double[points];
        // Each query adds its series up to one; without any sample in the range Prometheus sends none.
        for (var sample : ranges.isEmpty() ? List.<List<Object>>of() : ranges.getFirst().values()) {
            var at = Math.round(Double.parseDouble(sample.get(0).toString()));
            var index = (at - start.getEpochSecond()) / stepSeconds;
            var value = Double.parseDouble(sample.get(1).toString());
            if (index >= 0 && index < points && Double.isFinite(value)) {
                values[(int) index] = value;
            }
        }
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
