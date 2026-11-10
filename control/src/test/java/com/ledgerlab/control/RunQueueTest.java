package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.GATEWAY_ALL_WARM;
import static com.ledgerlab.control.RunFixtures.prometheus;
import static com.ledgerlab.control.RunFixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.control.runs.QueueScheduler;
import com.ledgerlab.control.runs.RunQueue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunQueueTest extends IntegrationTest {

    @Autowired RunQueue queue;
    @Autowired QueueScheduler scheduler;

    private record QueuedRun(UUID sessionId, Object runId) {
    }

    private List<Object> queued(int count) {
        var token = invite();
        return IntStream.rangeClosed(1, count).mapToObj(it -> start(sessionOf(token)).getBody().get("runId")).toList();
    }

    private QueuedRun queuedIn(String token) {
        var sessionId = sessionOf(token);
        return new QueuedRun(sessionId, start(sessionId).getBody().get("runId"));
    }

    private List<Object> stateAndWaitingFor(Map<String, Object> view) {
        return Arrays.asList(view.get("state"), view.get("waitingFor"));
    }

    @Test
    void a_run_waiting_longer_than_the_queue_timeout_expires() {
        var runs = queued(2);
        var stale = runs.get(0);
        var fresh = runs.get(1);
        setRun(stale, props.runs().queueTimeout().plusSeconds(1));
        setRun(fresh, props.runs().queueTimeout().minusSeconds(5));

        var step = queue.advance(false);

        assertThat(step.expired()).isEqualTo(1);
        assertThat(stateOf(stale)).isEqualTo("EXPIRED");
        assertThat(stateOf(fresh)).isEqualTo("QUEUED");
        assertThat(jdbc.sql("SELECT finished_at IS NOT NULL FROM runs WHERE run_id = CAST(:id AS uuid)").param("id", stale.toString()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void an_expired_run_is_not_admitted_even_with_free_places() {
        var stale = queued(1).get(0);
        setRun(stale, props.runs().queueTimeout().plusSeconds(1));

        assertThat(queue.advance(true).admitted()).isZero();
        assertThat(stateOf(stale)).isEqualTo("EXPIRED");
    }

    @Test
    void a_ready_ledger_lets_the_oldest_queued_runs_take_the_free_places() {
        var runs = queued(props.runs().maxActive() + 2);
        setRun(runs.get(0), "RUNNING");
        setRun(runs.get(1), "DRAINING");

        var step = queue.advance(true);

        assertThat(step.admitted()).isEqualTo(props.runs().maxActive() - 2);
        var expected = new ArrayList<String>(Collections.nCopies(props.runs().maxActive() - 2, "CREATED"));
        expected.addAll(Collections.nCopies(2, "QUEUED"));
        assertThat(runs.subList(2, runs.size()).stream().map(this::stateOf).toList())
            .containsExactly(expected.toArray(String[]::new));
    }

    private List<Object> queuedAt(int... tps) {
        var token = invite();
        return Arrays.stream(tps).mapToObj(it -> startWithTps(sessionOf(token), it).getBody().get("runId")).toList();
    }

    @Test
    void runs_whose_rates_fit_the_total_together_start_together() {
        var runs = queuedAt(500, 100, 100);

        assertThat(queue.advance(true).admitted()).isEqualTo(3);
        assertThat(runs.stream().map(this::stateOf).toList()).containsOnly("CREATED");
    }

    @Test
    void a_run_that_would_take_the_total_past_the_limit_waits_until_a_running_one_ends() {
        var runs = queuedAt(props.runs().maxTotalTps(), 100);
        var first = runs.get(0);
        var second = runs.get(1);

        assertThat(queue.advance(true).admitted()).isEqualTo(1);
        assertThat(stateOf(first)).isEqualTo("CREATED");
        assertThat(stateOf(second)).isEqualTo("QUEUED");

        setRun(first, "RECONCILED");

        assertThat(queue.advance(true).admitted()).isEqualTo(1);
        assertThat(stateOf(second)).isEqualTo("CREATED");
    }

    @Test
    void a_smaller_run_behind_a_waiting_one_does_not_overtake_it() {
        var runs = queuedAt(500, props.runs().maxTotalTps(), 100);
        var running = runs.get(0);
        var big = runs.get(1);
        var small = runs.get(2);
        assertThat(queue.advance(true).admitted()).isEqualTo(1);

        assertThat(queue.advance(true).admitted()).isZero();
        assertThat(Stream.of(running, big, small).map(this::stateOf).toList()).containsExactly("CREATED", "QUEUED", "QUEUED");
    }

    @Test
    void run_settings_with_a_rate_option_above_the_total_are_refused() {
        var aboveTotal = props.runs().maxTotalTps() + 1;
        var runs = props.runs();
        assertThatThrownBy(() -> new ControlProperties.Runs(
            runs.maxActive(), runs.maxQueued(), runs.queueTimeout(), List.of(aboveTotal), aboveTotal, runs.durationOptions(),
            runs.setupTimeout(), runs.runningGrace(), runs.drainStallTimeout(), runs.drainTimeout(), runs.accounts(),
            runs.maxTotalTps(), runs.maxConcurrentStarts(), runs.startWait()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("would never fit");
    }

    @Test
    void runs_that_fill_the_total_exactly_start() {
        var runs = queuedAt(props.runs().maxTotalTps() / 2, props.runs().maxTotalTps() / 2);

        assertThat(queue.advance(true).admitted()).isEqualTo(2);
        assertThat(runs.stream().map(this::stateOf).toList()).containsOnly("CREATED");
    }

    @Test
    void final_runs_free_their_places() {
        var runs = queued(props.runs().maxActive() + 1);
        runs.subList(0, props.runs().maxActive()).forEach(it -> setRun(it, "CREATED"));
        assertThat(queue.advance(true).admitted()).isZero();

        setRun(runs.get(0), "RECONCILED");

        assertThat(queue.advance(true).admitted()).isEqualTo(1);
        assertThat(stateOf(runs.getLast())).isEqualTo("CREATED");
    }

    @Test
    void no_run_leaves_the_queue_while_the_bank_is_not_ready() {
        var runs = queued(2);

        assertThat(queue.advance(false).admitted()).isZero();
        assertThat(runs.stream().map(this::stateOf).toList()).containsOnly("QUEUED");
    }

    @Test
    void positions_close_up_when_a_run_leaves_the_queue() {
        var token = invite();
        var runs = IntStream.rangeClosed(1, 3).mapToObj(it -> queuedIn(token)).toList();
        setRun(runs.get(0).runId(), "CREATED");

        assertThat(runs.stream().map(it -> getRun(it.sessionId(), it.runId()).getBody().get("position")).toList()).containsExactly(null, 1, 2);
    }

    @Test
    void a_tick_admits_runs_only_once_the_ledger_holds_every_partition() throws Exception {
        var run = queued(1).get(0);
        gatewayWarm(1);

        prometheus.status = 503;
        assertThat(scheduler.tick().admitted()).isZero();
        prometheus.status = 200;
        heldPartitions(props.ledger().transferPartitions() - 2);
        assertThat(scheduler.tick().admitted()).isZero();
        assertThat(stateOf(run)).isEqualTo("QUEUED");

        heldPartitions(props.ledger().transferPartitions());
        assertThat(scheduler.tick().admitted()).isEqualTo(1);
        assertThat(stateOf(run)).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("a run waits while the gateway warms up, with the ledger ready, and says so in its view")
    void a_run_waits_while_the_gateway_warms_up_with_the_ledger_ready_and_says_so_in_its_view() throws Exception {
        var token = invite();
        var sessionId = sessionOf(token);
        var run = start(sessionId).getBody().get("runId");
        heldPartitions(props.ledger().transferPartitions());
        gatewayWarm(0);

        assertThat(scheduler.tick().admitted()).isZero();
        assertThat(getRun(sessionId, run).getBody().get("waitingFor")).isEqualTo("WARM_UP");

        gatewayWarm(1);
        assertThat(scheduler.tick().admitted()).isEqualTo(1);
        assertThat(stateAndWaitingFor(getRun(sessionId, run).getBody())).isEqualTo(Arrays.asList("CREATED", null));
    }

    @Test
    @DisplayName("a run waits while the ledger takes its partitions, with the gateway warm, and says so in its view")
    void a_run_waits_while_the_ledger_takes_its_partitions_with_the_gateway_warm_and_says_so_in_its_view() throws Exception {
        var token = invite();
        var sessionId = sessionOf(token);
        var run = start(sessionId).getBody().get("runId");
        heldPartitions(props.ledger().transferPartitions() - 1);
        gatewayWarm(1);

        assertThat(scheduler.tick().admitted()).isZero();
        assertThat(getRun(sessionId, run).getBody().get("waitingFor")).isEqualTo("WARM_UP");

        heldPartitions(props.ledger().transferPartitions());
        assertThat(scheduler.tick().admitted()).isEqualTo(1);
        assertThat(stateAndWaitingFor(getRun(sessionId, run).getBody())).isEqualTo(Arrays.asList("CREATED", null));
    }

    @Test
    void a_queued_run_waiting_only_for_its_place_does_not_say_the_bank_is_getting_ready() throws Exception {
        var token = invite();
        var runs = IntStream.rangeClosed(1, props.runs().maxActive() + 1).mapToObj(it -> queuedIn(token)).toList();
        runs.subList(0, props.runs().maxActive()).forEach(it -> setRun(it.runId(), "CREATED"));
        heldPartitions(props.ledger().transferPartitions());
        gatewayWarm(1);

        assertThat(scheduler.tick().admitted()).isZero();

        var last = runs.getLast();
        assertThat(stateAndWaitingFor(getRun(last.sessionId(), last.runId()).getBody())).isEqualTo(Arrays.asList("QUEUED", null));
    }

    private void gatewayWarm(int value) {
        prometheus.answerQuery(
            "GET", "/api/v1/query", "query=" + GATEWAY_ALL_WARM, 200, prometheus(List.of(sample(Map.of(), value))));
    }

    private void heldPartitions(int count) {
        prometheus.answerQuery(
            "GET", "/api/v1/query", "query=sum(ledger_kafka_assigned_partitions)", 200, prometheus(List.of(sample(Map.of(), count))));
    }
}
