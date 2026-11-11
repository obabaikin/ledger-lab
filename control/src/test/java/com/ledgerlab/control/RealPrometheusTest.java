package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** Control's queries against a real Prometheus scraping fake services: a canned answer cannot show what PromQL does (E97). */
@TestPropertySource(properties = {"control.series.range=1m", "control.series.step=1s"})
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
    /** A chart has a value at the next instant of its grid after the samples. */
    private static final Duration CHART_SETTLES = Duration.ofSeconds(15);

    static final FakeExporter exporter = new FakeExporter(JOBS);
    static final FakeExporter killedExporter = new FakeExporter(List.of(KILLED_LEDGER));
    static final FakeExporter gatewayExporter = new FakeExporter(List.of(GATEWAY));
    static final FakeExporter secondGateway = new FakeExporter(List.of(GATEWAY));

    private record Target(String job, List<Integer> ports) {
    }

    private static final class RealPrometheus {
        static final GenericContainer<?> CONTAINER = started();

        private static GenericContainer<?> started() {
            Testcontainers.exposeHostPorts(exporter.port, killedExporter.port, gatewayExporter.port, secondGateway.port);
            var targets = new ArrayList<Target>();
            JOBS.forEach(it -> targets.add(new Target(it, List.of(exporter.port))));
            targets.add(new Target(KILLED_LEDGER, List.of(killedExporter.port)));
            targets.add(new Target(GATEWAY, List.of(gatewayExporter.port, secondGateway.port)));
            var jobs = targets.stream()
                .map(target ->
                    "  - job_name: " + target.job() + "\n"
                        + "    metrics_path: /" + target.job() + "\n"
                        + "    static_configs:\n"
                        + "      - targets: ["
                        + target.ports().stream().map(it -> "\"host.testcontainers.internal:" + it + "\"").collect(Collectors.joining(", ")) + "]\n"
                        + "        " + (target.job().equals(NODE_EXPORTER) ? "labels: {}" : "labels: {lab_service: \"true\"}"))
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

    @DynamicPropertySource
    static void realPrometheus(DynamicPropertyRegistry registry) {
        registry.add(
            "control.prometheus.base-url",
            () -> "http://" + RealPrometheus.CONTAINER.getHost() + ":" + RealPrometheus.CONTAINER.getMappedPort(PROMETHEUS_PORT));
    }

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
    @DisplayName("the charts show the rate of accepted transfers over the gateway's instances and the bank's latency in milliseconds")
    void the_charts_show_the_rate_of_accepted_transfers_over_the_gateways_instances_and_the_banks_latency_in_milliseconds() throws Exception {
        exporter.serve(LEDGER, "reporting_bank_latency_seconds{quantile=\"0.99\"} 0.25");
        for (var accepted = 100; accepted <= 400; accepted += 100) {
            var counters = "gateway_transfers_total{outcome=\"accepted\"} " + accepted + "\ngateway_transfers_total{outcome=\"refused\"} 7";
            gatewayExporter.serve(GATEWAY, counters);
            secondGateway.serve(GATEWAY, counters);
            gatewayExporter.awaitScrapes(SCRAPES_TO_SETTLE);
        }

        var deadline = Instant.now().plus(CHART_SETTLES);
        while (valuesOf("ACCEPTED_PER_SECOND").stream().noneMatch(it -> it > 0) || valuesOf("BANK_LATENCY_P99_MS").isEmpty()) {
            assertThat(Instant.now()).as("the charts have values").isBefore(deadline);
            Thread.sleep(POLL);
        }

        assertThat(valuesOf("BANK_LATENCY_P99_MS").getLast()).isEqualTo(250.0);
        assertThat(valuesOf("BUSY_PER_SECOND")).allMatch(it -> it == 0.0);
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
