package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.reporting.domain.RunStats;
import com.ledgerlab.reporting.domain.SecondStats;
import com.ledgerlab.reporting.persistence.AcceptedTransfer;
import com.ledgerlab.reporting.persistence.RunStatsRepository;
import com.ledgerlab.reporting.persistence.TransferOutcomeRow;
import com.ledgerlab.reporting.persistence.TransferStatsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@AutoConfigureTestRestTemplate
class RunStatsTest extends IntegrationTest {

    @Autowired TransferStatsRepository transfers;
    @Autowired RunStatsRepository stats;
    @Autowired TestRestTemplate http;

    private final Instant t0 = Instant.parse("2026-10-13T10:00:00Z");

    @Test
    void an_unknown_run_has_zeros_and_an_empty_series() {
        var snapshot = stats.snapshot(UUID.randomUUID());

        assertThat(snapshot.totals().accepted()).isZero();
        assertThat(snapshot.totals().inFlight()).isZero();
        assertThat(snapshot.totals().rejectedByReason()).isEmpty();
        assertThat(snapshot.bankLatency().p50Ms()).isNull();
        assertThat(snapshot.series()).isEmpty();
    }

    @Test
    @DisplayName("totals count accepted, posted, rejected by reason and the posted amount")
    void totals_count_accepted_posted_rejected_by_reason_and_the_posted_amount() {
        var runId = UUID.randomUUID();
        var a = command(runId, 100, t0);
        var b = command(runId, 200, t0);
        var c = command(runId, 300, t0);
        var d = command(runId, 400, t0);
        store(a, b, c, d);
        store(posted(a, t0.plusMillis(10)), posted(b, t0.plusMillis(20)), rejected(c, RejectReason.INSUFFICIENT_FUNDS, t0.plusMillis(30)));

        var totals = stats.snapshot(runId).totals();

        assertThat(totals.accepted()).isEqualTo(4);
        assertThat(totals.posted()).isEqualTo(2);
        assertThat(totals.rejected()).isEqualTo(1);
        assertThat(totals.inFlight()).isEqualTo(1);
        assertThat(totals.postedAmountCents()).isEqualTo(300);
        assertThat(totals.rejectedByReason()).containsExactly(entry(RejectReason.INSUFFICIENT_FUNDS.name(), 1L));
    }

    @Test
    void the_series_counts_acceptance_by_acceptedAt_and_outcomes_by_processedAt() {
        var runId = UUID.randomUUID();
        var a = command(runId, 100, t0.plusMillis(100));
        var b = command(runId, 200, t0.plusMillis(900));
        store(a, b);
        store(posted(a, t0.plusMillis(1_200)), rejected(b, RejectReason.BLOCKED_ACCOUNT, t0.plusMillis(2_500)));

        var series = stats.snapshot(runId).series();

        assertThat(series).extracting(SecondStats::second).containsExactly(t0, t0.plusSeconds(1), t0.plusSeconds(2));
        assertThat(series).extracting(SecondStats::accepted).containsExactly(2L, 0L, 0L);
        assertThat(series).extracting(SecondStats::posted).containsExactly(0L, 1L, 0L);
        assertThat(series).extracting(SecondStats::rejected).containsExactly(0L, 0L, 1L);
        assertThat(series).extracting(SecondStats::postedAmountCents).containsExactly(0L, 100L, 0L);
    }

    @Test
    @DisplayName("a result seen before its command is accepted in the second of the result, so in flight never goes below zero")
    void a_result_seen_before_its_command_is_accepted_in_the_second_of_the_result_so_in_flight_never_goes_below_zero() {
        var runId = UUID.randomUUID();
        var early = command(runId, 100, t0.plusMillis(500));
        var late = command(runId, 200, t0.plusMillis(600));
        store(early);
        store(posted(early, t0.plusMillis(1_100)), posted(late, t0.plusMillis(2_100)));

        var snapshot = stats.snapshot(runId);

        assertThat(snapshot.totals().accepted()).isEqualTo(2);
        assertThat(snapshot.totals().commandsSeen()).isEqualTo(1);
        assertThat(snapshot.totals().inFlight()).isZero();
        assertThat(snapshot.totals().postedAmountCents()).isEqualTo(100);
        assertThat(runningInFlight(snapshot)).allMatch(inFlight -> inFlight >= 0);
        assertThat(snapshot.series().getLast().bankP50Ms()).isNull();

        store(late);

        var complete = stats.snapshot(runId);
        assertThat(complete.totals().commandsSeen()).isEqualTo(2);
        assertThat(complete.series().getFirst().accepted()).isEqualTo(2);
        assertThat(complete.totals().postedAmountCents()).isEqualTo(300);
        assertThat(complete.series().getLast().bankP50Ms()).isEqualTo(1_500.0);
    }

    @Test
    @DisplayName("bank latency is processedAt minus acceptedAt, per second and over the run")
    void bank_latency_is_processedAt_minus_acceptedAt_per_second_and_over_the_run() {
        var runId = UUID.randomUUID();
        var commands = Stream.generate(() -> command(runId, 10, t0)).limit(4).toList();
        store(commands.toArray(TransferCommand[]::new));
        var results = new ArrayList<TransferResultEvent>();
        for (var i = 0; i < commands.size(); i++) {
            results.add(posted(commands.get(i), t0.plusMillis(100L * (i + 1))));
        }
        store(results.toArray(TransferResultEvent[]::new));

        var snapshot = stats.snapshot(runId);

        assertThat(snapshot.bankLatency().p50Ms()).isEqualTo(250.0);
        assertThat(snapshot.bankLatency().p99Ms()).isEqualTo(397.0);
        assertThat(snapshot.series()).singleElement().extracting(SecondStats::bankP50Ms).isEqualTo(250.0);
    }

    @Test
    void storing_the_same_events_again_changes_nothing() {
        var runId = UUID.randomUUID();
        var a = command(runId, 100, t0);
        store(a, a);
        store(posted(a, t0.plusMillis(5)));
        var before = stats.snapshot(runId);

        store(a);
        store(posted(a, t0.plusMillis(5)), posted(a, t0.plusMillis(5)));

        var after = stats.snapshot(runId);
        assertThat(new RunStats(after.runId(), before.at(), after.totals(), after.bankLatency(), after.series())).isEqualTo(before);
    }

    @Test
    void the_API_deletes_a_run_once_and_then_answers_not_found() {
        var runId = UUID.randomUUID();
        store(command(runId, 100, t0));

        assertThat(http.getForObject("/internal/runs/" + runId + "/stats", RunStats.class).totals().accepted()).isEqualTo(1);
        assertThat(delete(runId)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(runId)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stats.snapshot(runId).totals().accepted()).isZero();
    }

    private HttpStatusCode delete(UUID runId) {
        return http.exchange("/internal/runs/" + runId, HttpMethod.DELETE, null, Void.class).getStatusCode();
    }

    private List<Long> runningInFlight(RunStats snapshot) {
        var running = new ArrayList<Long>();
        var inFlight = 0L;
        running.add(inFlight);
        for (var s : snapshot.series()) {
            inFlight += s.accepted() - s.posted() - s.rejected();
            running.add(inFlight);
        }
        return running;
    }

    private void store(TransferCommand... commands) {
        transfers.upsertAccepted(Arrays.stream(commands)
            .map(it -> new AcceptedTransfer(it.transferId(), it.runId(), it.amountCents(), it.createdAt()))
            .toList());
    }

    private void store(TransferResultEvent... results) {
        transfers.upsertOutcomes(Arrays.stream(results)
            .map(it -> new TransferOutcomeRow(
                it.transferId(), it.runId(), it.outcome().name(), it.rejectReason() == null ? null : it.rejectReason().name(), it.processedAt()))
            .toList());
    }
}
