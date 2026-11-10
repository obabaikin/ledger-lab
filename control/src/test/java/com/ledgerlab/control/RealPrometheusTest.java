package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.simulation;
import static com.ledgerlab.control.http.ServiceHttp.forRun;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayWarmUp;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.ledger.LedgerReadiness;
import com.ledgerlab.control.runs.Conductor;
import com.ledgerlab.control.runs.RunQueue;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** Control's queries against a real Prometheus scraping fake services: a canned answer cannot show what PromQL does (E97). */
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

    static final FakeExporter exporter = new FakeExporter(JOBS);
    static final FakeExporter killedExporter = new FakeExporter(List.of(KILLED_LEDGER));
    static final FakeExporter gatewayExporter = new FakeExporter(List.of(GATEWAY));
    /** A gateway instance the test makes unreachable by closing its exporter. */
    static final FakeExporter unscrapedGateway = new FakeExporter(List.of(GATEWAY));

    @Autowired Conductor conductor;
    @Autowired RunQueue queue;
    @Autowired LedgerReadiness readiness;
    @Autowired GatewayWarmUp warmUp;

    private record Running(UUID sessionId, UUID runId) {
    }

    private record Target(String job, List<Integer> ports) {
    }

    private static final class RealPrometheus {
        static final GenericContainer<?> CONTAINER = started();

        private static GenericContainer<?> started() {
            Testcontainers.exposeHostPorts(exporter.port, killedExporter.port, gatewayExporter.port, unscrapedGateway.port);
            var targets = new ArrayList<Target>();
            JOBS.forEach(it -> targets.add(new Target(it, List.of(exporter.port))));
            targets.add(new Target(KILLED_LEDGER, List.of(killedExporter.port)));
            targets.add(new Target(GATEWAY, List.of(gatewayExporter.port, unscrapedGateway.port)));
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

    private Running running() {
        var sessionId = sessionOf(invite());
        var runId = UUID.fromString((String) start(sessionId).getBody().get("runId"));
        queue.advance(true);
        jdbc.sql("UPDATE runs SET state = 'RUNNING', started_at = clock_timestamp() WHERE run_id = :id").param("id", runId).update();
        generator.answer("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), 200, simulation(runId, "RUNNING"));
        return new Running(sessionId, runId);
    }

    private Map<?, ?> live(UUID sessionId, UUID runId) {
        return Objects.requireNonNull((Map<?, ?>) getRun(sessionId, runId).getBody().get("live"));
    }

    private List<Object> upAndPartitions(Map<?, ?> pod) {
        return Arrays.asList(pod.get("up"), pod.get("partitions"));
    }

    @Test
    @DisplayName("a ledger pod shows its partitions next to whether it answered, other services none")
    void a_ledger_pod_shows_its_partitions_next_to_whether_it_answered_other_services_none() throws Exception {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        exporter.serve(LEDGER, "ledger_kafka_assigned_partitions 3");
        exporter.serve(SECOND_LEDGER, "");
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);

        conductor.tick();

        var pods = new HashMap<Object, Map<?, ?>>();
        ((List<?>) live(sessionId, runId).get("pods")).forEach(it -> pods.put(((Map<?, ?>) it).get("job"), (Map<?, ?>) it));
        assertThat(upAndPartitions(Objects.requireNonNull(pods.get(LEDGER)))).isEqualTo(Arrays.asList(true, 3));
        assertThat(upAndPartitions(Objects.requireNonNull(pods.get(SECOND_LEDGER)))).isEqualTo(Arrays.asList(true, null));
    }

    @Test
    @DisplayName("the pods are the project's services only, not the exporters Prometheus scrapes beside them")
    void the_pods_are_the_projects_services_only_not_the_exporters_Prometheus_scrapes_beside_them() throws Exception {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        exporter.serve(LEDGER, "");
        exporter.serve(SECOND_LEDGER, "");
        exporter.serve(NODE_EXPORTER, "node_load1 0.5");
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);

        conductor.tick();

        assertThat(((List<?>) live(sessionId, runId).get("pods")).stream().<Object>map(it -> ((Map<?, ?>) it).get("job")).toList())
            .contains(LEDGER, SECOND_LEDGER).doesNotContain(NODE_EXPORTER);
    }

    @Test
    @DisplayName("the ledger's duplicates of a run are summed over its instances, apart from a run beside it")
    void the_ledgers_duplicates_of_a_run_are_summed_over_its_instances_apart_from_a_run_beside_it() throws Exception {
        var run = running();
        var sessionId = run.sessionId();
        var runId = run.runId();
        var other = UUID.randomUUID();
        exporter.serve(LEDGER, "ledger_duplicates_total{run=\"" + runId + "\"} 4\nledger_duplicates_total{run=\"" + other + "\"} 50");
        exporter.serve(SECOND_LEDGER, "ledger_duplicates_total{run=\"" + runId + "\"} 3");
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);

        conductor.tick();

        assertThat(live(sessionId, runId).get("ledgerDuplicatesAtLeast")).isEqualTo(7);
    }

    @Test
    @DisplayName("a killed instance's partitions stop counting, so the ledger is not ready until the live ones hold all")
    void a_killed_instances_partitions_stop_counting_so_the_ledger_is_not_ready_until_the_live_ones_hold_all() throws Exception {
        exporter.serve(LEDGER, "ledger_kafka_assigned_partitions 2");
        exporter.serve(SECOND_LEDGER, "ledger_kafka_assigned_partitions 2");
        killedExporter.serve(KILLED_LEDGER, "ledger_kafka_assigned_partitions " + (props.ledger().transferPartitions() - 4));
        killedExporter.awaitScrapes(SCRAPES_TO_SETTLE);
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);
        assertThat(readiness.isReady()).isTrue();

        killedExporter.close();
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);
        assertThat(readiness.isReady()).isFalse();

        exporter.serve(SECOND_LEDGER, "ledger_kafka_assigned_partitions " + (props.ledger().transferPartitions() - 2));
        exporter.awaitScrapes(SCRAPES_TO_SETTLE);
        assertThat(readiness.isReady()).isTrue();
    }

    @Test
    @DisplayName("a gateway instance Prometheus cannot scrape holds the runs back, though the other one is warm")
    void a_gateway_instance_Prometheus_cannot_scrape_holds_the_runs_back_though_the_other_one_is_warm() throws Exception {
        gatewayExporter.serve(GATEWAY, "gateway_warm_up_complete 1");
        unscrapedGateway.serve(GATEWAY, "gateway_warm_up_complete 1");
        unscrapedGateway.awaitScrapes(SCRAPES_TO_SETTLE);
        gatewayExporter.awaitScrapes(SCRAPES_TO_SETTLE);
        assertThat(warmUp.check()).isTrue();

        unscrapedGateway.close();
        gatewayExporter.awaitScrapes(SCRAPES_TO_SETTLE);
        assertThat(warmUp.check()).isFalse();
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
