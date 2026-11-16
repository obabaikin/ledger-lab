package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ledgerlab.control.availability.Availability;
import com.ledgerlab.control.availability.PostingCollector;
import com.ledgerlab.control.metrics.BankMetrics;
import com.ledgerlab.control.metrics.PrometheusClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** Control's queries against a real Prometheus scraping fake services: a canned answer cannot show what PromQL does (E97). */
@TestPropertySource(properties = {
    "control.series.ranges[0].range=1m", "control.series.ranges[0].step=1s", "control.series.ranges[0].window=5s", "control.series.default-range=1m"})
class RealPrometheusTest extends IntegrationTest {

    private static final String LEDGER = "ledger";
    private static final String SECOND_LEDGER = "ledger-b";
    /** Scraped without the project's label, as the chart's exporters are. */
    private static final String NODE_EXPORTER = "node-exporter";
    private static final List<String> JOBS = List.of(LEDGER, SECOND_LEDGER, NODE_EXPORTER);
    /** A ledger instance the test kills by closing its exporter. */
    private static final String KILLED_LEDGER = "ledger-killed";
    /** Two instances under one job, as the chart's replicas are. */
    private static final String GATEWAY = "gateway";
    private static final int PROMETHEUS_PORT = 9090;
    private static final Duration SCRAPE_INTERVAL = Duration.ofSeconds(1);
    private static final Duration POLL = Duration.ofMillis(20);
    /** Prometheus starts scraping new targets only seconds after it is ready. */
    private static final Duration TARGETS_DISCOVERED = Duration.ofSeconds(30);
    /** A sample is queryable once the next scrape came. */
    private static final int SCRAPES_TO_SETTLE = 2;
    private static final Duration STARTS_POLL = Duration.ofMillis(500);
    /** A chart has a value at the next instant of its grid after the samples. */
    private static final Duration CHART_SETTLES = Duration.ofSeconds(15);

    static final FakeExporter exporter = new FakeExporter(JOBS);
    static final FakeExporter killedExporter = new FakeExporter(List.of(KILLED_LEDGER));
    static final FakeExporter gatewayExporter = new FakeExporter(List.of(GATEWAY));
    static final FakeExporter secondGateway = new FakeExporter(List.of(GATEWAY));
    /** Served only by the test of the buckets, so no other test's counters move in its minute. */
    static final FakeExporter updatedGateway = new FakeExporter(List.of(GATEWAY));
    static final FakeExporter oldGateway = new FakeExporter(List.of(GATEWAY));
    /** The tests of the minute's buckets each sum every gateway instance over a minute, so they wait for each other's to pass. */
    private static final Duration BUCKET_MINUTE = Duration.ofSeconds(61);
    private static volatile Instant bucketsServedUntil = Instant.EPOCH;

    private static void awaitOtherBucketsOutOfTheMinute() throws InterruptedException {
        var quiet = bucketsServedUntil.plus(BUCKET_MINUTE);
        while (Instant.now().isBefore(quiet)) {
            Thread.sleep(POLL);
        }
    }

    /** Served only by the test of an instance that stops within the minute. */
    static final FakeExporter steadyGateway = new FakeExporter(List.of(GATEWAY));
    static final FakeExporter stoppingGateway = new FakeExporter(List.of(GATEWAY));

    private record Target(String job, List<Integer> ports) {
    }

    private static final class RealPrometheus {
        static final GenericContainer<?> CONTAINER = started();

        private static GenericContainer<?> started() {
            Testcontainers.exposeHostPorts(
                exporter.port, killedExporter.port, gatewayExporter.port, secondGateway.port, updatedGateway.port, oldGateway.port,
                steadyGateway.port, stoppingGateway.port);
            var targets = new ArrayList<Target>();
            JOBS.forEach(it -> targets.add(new Target(it, List.of(exporter.port))));
            targets.add(new Target(KILLED_LEDGER, List.of(killedExporter.port)));
            targets.add(new Target(GATEWAY, List.of(
                gatewayExporter.port, secondGateway.port, updatedGateway.port, oldGateway.port, steadyGateway.port, stoppingGateway.port)));
            var jobs = targets.stream()
                .map(target ->
                    "  - job_name: " + target.job() + "\n"
                        + "    metrics_path: /" + target.job() + "\n"
                        + "    static_configs:\n"
                        + "      - targets: ["
                        + target.ports().stream().map(it -> "\"host.testcontainers.internal:" + it + "\"").collect(Collectors.joining(", ")) + "]\n"
                        + "        labels: {" + labels(target) + "}")
                .collect(Collectors.joining("\n"));
            var config = "global:\n  scrape_interval: " + SCRAPE_INTERVAL.toSeconds() + "s\n  scrape_timeout: 800ms\nscrape_configs:\n" + jobs + "\n";
            var container = new GenericContainer<>("prom/prometheus:v3.5.0")
                .withCopyToContainer(Transferable.of(config), "/etc/prometheus/prometheus.yml")
                .withExposedPorts(PROMETHEUS_PORT)
                .waitingFor(Wait.forHttp("/-/ready").forPort(PROMETHEUS_PORT));
            container.start();
            try {
                exporter.awaitScrapes(1, TARGETS_DISCOVERED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return container;
        }
    }

    /** A job of one target is named as the chart's Prometheus names a pod, by its name; the exporter lacks the project's label. */
    private static String labels(Target target) {
        var labels = new ArrayList<String>();
        if (!target.job().equals(NODE_EXPORTER)) {
            labels.add("lab_service: \"true\"");
        }
        if (target.ports().size() == 1) {
            labels.add("instance: \"" + target.job() + "\"");
        }
        return String.join(", ", labels);
    }

    @DynamicPropertySource
    static void realPrometheus(DynamicPropertyRegistry registry) {
        registry.add(
            "control.prometheus.base-url",
            () -> "http://" + RealPrometheus.CONTAINER.getHost() + ":" + RealPrometheus.CONTAINER.getMappedPort(PROMETHEUS_PORT));
    }

    @Autowired PrometheusClient prometheusClient;
    @Autowired PostingCollector collector;
    @Autowired Availability availability;
    @Autowired DatabaseClock clock;

    private Map<Object, Map<?, ?>> podsByJob() {
        conductor.tick();
        var pods = new HashMap<Object, Map<?, ?>>();
        ((List<?>) Objects.requireNonNull(get("/api/bank").getBody().get("pods"))).forEach(it -> pods.put(((Map<?, ?>) it).get("job"), (Map<?, ?>) it));
        return pods;
    }

    private List<Object> upAndPartitions(Map<?, ?> pod) {
        return Arrays.asList(pod.get("up"), pod.get("partitions"));
    }

    private List<Double> valuesOf(String name) {
        var series = (List<?>) get("/api/bank/series").getBody().get("series");
        return series.stream()
            .map(it -> (Map<?, ?>) it)
            .filter(it -> name.equals(it.get("name")))
            .flatMap(it -> ((List<?>) it.get("values")).stream())
            .filter(Objects::nonNull)
            .map(it -> ((Number) it).doubleValue())
            .toList();
    }

    @Test
    @DisplayName("a ledger pod shows its partitions next to whether it answered, other services none")
    void a_ledger_pod_shows_its_partitions_next_to_whether_it_answered_other_services_none() throws Exception {
        exporter.serve(LEDGER, "ledger_kafka_assigned_partitions 3");
        exporter.serve(SECOND_LEDGER, "");
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);

        var pods = podsByJob();

        assertThat(upAndPartitions(Objects.requireNonNull(pods.get(LEDGER)))).isEqualTo(Arrays.asList(true, 3));
        assertThat(upAndPartitions(Objects.requireNonNull(pods.get(SECOND_LEDGER)))).isEqualTo(Arrays.asList(true, null));
    }

    @Test
    @DisplayName("the pods are the project's services only, not the exporters Prometheus scrapes beside them")
    void the_pods_are_the_projects_services_only_not_the_exporters_Prometheus_scrapes_beside_them() throws Exception {
        exporter.serve(LEDGER, "");
        exporter.serve(SECOND_LEDGER, "");
        exporter.serve(NODE_EXPORTER, "node_load1 0.5");
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);

        assertThat(podsByJob().keySet()).contains(LEDGER, SECOND_LEDGER).doesNotContain(NODE_EXPORTER);
    }

    @Test
    @DisplayName("a killed instance is shown as down")
    void a_killed_instance_is_shown_as_down() throws Exception {
        killedExporter.serve(KILLED_LEDGER, "ledger_kafka_assigned_partitions 2");
        killedExporter.awaitScrapes(SCRAPES_TO_SETTLE);
        assertThat(Objects.requireNonNull(podsByJob().get(KILLED_LEDGER)).get("up")).isEqualTo(true);

        killedExporter.close();
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);

        assertThat(Objects.requireNonNull(podsByJob().get(KILLED_LEDGER)).get("up")).isEqualTo(false);
    }

    @Test
    @DisplayName("the charts show the rate of accepted transfers over the gateway's instances and p50 and p99 from its histogram in milliseconds")
    void the_charts_show_the_rate_of_accepted_transfers_over_the_gateways_instances_and_p50_and_p99_from_its_histogram_in_milliseconds()
        throws Exception {
        for (var round = 1; round <= 4; round++) {
            var metrics = "gateway_transfers_total{outcome=\"accepted\"} " + round * 110 + "\ngateway_transfers_total{outcome=\"refused\"} 7\n"
                + histogram(round * 100, round * 100, round * 110, round * 110);
            gatewayExporter.serve(GATEWAY, metrics);
            secondGateway.serve(GATEWAY, metrics);
            gatewayExporter.awaitScrapes(SCRAPES_TO_SETTLE);
        }

        var deadline = Instant.now().plus(CHART_SETTLES);
        while (valuesOf("ACCEPTED_PER_SECOND").stream().noneMatch(it -> it > 0) || valuesOf("BANK_LATENCY_P99_MS").isEmpty()) {
            assertThat(Instant.now()).as("the charts have values").isBefore(deadline);
            Thread.sleep(POLL);
        }

        assertThat(valuesOf("BANK_LATENCY_P50_MS").getLast()).isCloseTo(55.0, within(0.001));
        assertThat(valuesOf("BANK_LATENCY_P99_MS").getLast()).isCloseTo(4780.0, within(0.001));
        assertThat(valuesOf("BUSY_PER_SECOND")).allMatch(it -> it == 0.0);
    }

    @Test
    @DisplayName("a gateway instance without the buckets at 3 s and 5 s, as before an update, is counted neither in the transfers nor within the thresholds")
    void a_gateway_instance_without_the_buckets_at_3_s_and_5_s_is_counted_neither_in_the_transfers_nor_within_the_thresholds() throws Exception {
        awaitOtherBucketsOutOfTheMinute();
        // Far above what the charts' test served, so what it still adds in the minute does not show.
        var scale = 1_000_000L;
        for (var round = 1; round <= 4; round++) {
            updatedGateway.serve(GATEWAY, histogram(round * scale * 90 / 100, round * scale * 96 / 100, round * scale * 99 / 100, round * scale));
            oldGateway.serve(GATEWAY, histogramBefore3s(round * scale * 10, round * scale * 10));
            updatedGateway.awaitScrapes(SCRAPES_TO_SETTLE);
        }

        var transfers = single(Objects.requireNonNull(prometheusClient.query(
            BankMetrics.transfersInMinute(List.of(Duration.ofSeconds(3), Duration.ofSeconds(5)))))).number();
        var within3s = single(Objects.requireNonNull(prometheusClient.query(BankMetrics.WITHIN_IN_MINUTE.formatted("3.0")))).number();
        var within5s = single(Objects.requireNonNull(prometheusClient.query(BankMetrics.WITHIN_IN_MINUTE.formatted("5.0")))).number();
        bucketsServedUntil = Instant.now();

        assertThat(transfers).isPositive();
        assertThat(within3s / transfers).isCloseTo(0.96, within(0.001));
        assertThat(within5s / transfers).isCloseTo(0.99, within(0.001));
    }

    @Test
    @DisplayName("a gateway instance that stops within the minute counts in the transfers as in those within the thresholds, never more within than in all")
    void a_gateway_instance_that_stops_within_the_minute_counts_in_the_transfers_as_in_those_within_the_thresholds() throws Exception {
        awaitOtherBucketsOutOfTheMinute();
        var scale = 1_000_000L;
        for (var round = 1; round <= 4; round++) {
            steadyGateway.serve(GATEWAY, histogram(round * scale / 2, round * scale / 2, round * scale / 2, round * scale));
            if (round < 4) {
                stoppingGateway.serve(GATEWAY, histogram(round * scale, round * scale, round * scale, round * scale));
            } else {
                stoppingGateway.serve(GATEWAY, "");
            }
            steadyGateway.awaitScrapes(SCRAPES_TO_SETTLE);
        }

        var transfers = single(Objects.requireNonNull(prometheusClient.query(
            BankMetrics.transfersInMinute(List.of(Duration.ofSeconds(3), Duration.ofSeconds(5)))))).number();
        var within3s = single(Objects.requireNonNull(prometheusClient.query(BankMetrics.WITHIN_IN_MINUTE.formatted("3.0")))).number();
        bucketsServedUntil = Instant.now();

        assertThat(within3s / transfers).isBetween(0.5, 1.0);
    }

    /** As the gateway's timer publishes it, with buckets at 3 s and 5 s. */
    private static String histogram(long within100ms, long within3s, long within5s, long count) {
        return """
            # TYPE gateway_transfer_latency_seconds histogram
            gateway_transfer_latency_seconds_bucket{le="0.1"} %d
            gateway_transfer_latency_seconds_bucket{le="3.0"} %d
            gateway_transfer_latency_seconds_bucket{le="5.0"} %d
            gateway_transfer_latency_seconds_bucket{le="+Inf"} %d
            gateway_transfer_latency_seconds_count %d
            gateway_transfer_latency_seconds_sum %d
            """.formatted(within100ms, within3s, within5s, count, count, count);
    }

    /** As a gateway published it before the buckets at 3 s and 5 s, with one at 2 s. */
    private static String histogramBefore3s(long within2s, long count) {
        return """
            # TYPE gateway_transfer_latency_seconds histogram
            gateway_transfer_latency_seconds_bucket{le="2.0"} %d
            gateway_transfer_latency_seconds_bucket{le="+Inf"} %d
            gateway_transfer_latency_seconds_count %d
            gateway_transfer_latency_seconds_sum %d
            """.formatted(within2s, count, count, count);
    }

    @Test
    @DisplayName("a crashed pod started again leaves the crash outside, an exporter's restart too; a restart of another pod makes it judged")
    void a_crashed_pod_started_again_leaves_the_crash_outside_an_exporters_restart_too_a_restart_of_another_pod_makes_it_judged()
        throws Exception {
        ReflectionTestUtils.setField(collector, "startsReadUpTo", null);
        var crashedAt = clock.now().minusSeconds(30);
        exporter.serve(NODE_EXPORTER, startedAt(crashedAt.minus(Duration.ofHours(1))));
        started(LEDGER, crashedAt.minus(Duration.ofHours(1)));
        started(SECOND_LEDGER, crashedAt.minus(Duration.ofHours(1)));
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (:at, ARRAY[:crashed])")
            .param("at", Timestamp.from(crashedAt))
            .param("crashed", LEDGER)
            .update();
        jdbc.sql("INSERT INTO availability_minutes (minute, total, errors, busy, transfers, within_typical, within_tail) VALUES (:minute, 100, 50, 0, 100, 50, 50)")
            .param("minute", Timestamp.from(crashedAt.truncatedTo(ChronoUnit.MINUTES)))
            .update();

        exporter.serve(NODE_EXPORTER, startedAt(crashedAt.plusSeconds(20)));
        started(LEDGER, crashedAt.plusSeconds(20));

        assertThat(availability.slo(clock.now()).outsideMinutes()).isEqualTo(1);
        assertThat(kept(NODE_EXPORTER, crashedAt.plusSeconds(20))).isFalse();
        started(SECOND_LEDGER, crashedAt.plusSeconds(25));
        assertThat(availability.slo(clock.now()).outsideMinutes()).isZero();
        assertThat(availability.slo(clock.now()).badMinutes()).isEqualTo(1);
    }

    private static String startedAt(Instant at) {
        return "process_start_time_seconds " + at.toEpochMilli() / 1000.0;
    }

    /** The exporter's scrapes count every job's, so the collector reads until it has seen the start. */
    private void started(String job, Instant at) throws InterruptedException {
        exporter.serve(job, startedAt(at));
        var deadline = Instant.now().plus(CHART_SETTLES);
        while (Instant.now().isBefore(deadline) && !kept(job, at)) {
            collector.collect(clock.now());
            Thread.sleep(STARTS_POLL);
        }
        assertThat(kept(job, at)).as("the start of %s is kept", job).isTrue();
    }

    private boolean kept(String job, Instant at) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM service_starts WHERE instance = :job AND started_at = :at)")
            .param("job", job)
            .param("at", Timestamp.from(at.truncatedTo(ChronoUnit.MILLIS)))
            .query(Boolean.class)
            .single();
    }

    /** Serves each job's metrics as text and counts the scrapes, so a test knows Prometheus has seen what it serves. */
    static final class FakeExporter implements AutoCloseable {
        private final List<String> jobs;
        private final ConcurrentHashMap<String, String> bodies = new ConcurrentHashMap<>();
        private final AtomicInteger scrapes = new AtomicInteger();

        private final HttpServer server;

        final int port;

        FakeExporter(List<String> jobs) {
            this.jobs = jobs;
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            port = server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            var path = exchange.getRequestURI().getPath();
            var body = bodies.getOrDefault(path.startsWith("/") ? path.substring(1) : path, "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length > 0 ? body.length : -1);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
            scrapes.incrementAndGet();
        }

        void serve(String job, String metrics) {
            bodies.put(job, metrics.stripIndent() + "\n");
        }

        void awaitScrapes(int perJob) throws InterruptedException {
            awaitScrapes(perJob, SCRAPE_INTERVAL.multipliedBy(perJob + 2L));
        }

        void awaitScrapes(int perJob, Duration within) throws InterruptedException {
            var until = scrapes.get() + perJob * jobs.size();
            var deadline = Instant.now().plus(within);
            while (scrapes.get() < until) {
                if (!Instant.now().isBefore(deadline)) {
                    throw new IllegalStateException("Prometheus did not scrape: " + scrapes.get() + " of " + until);
                }
                Thread.sleep(POLL);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
