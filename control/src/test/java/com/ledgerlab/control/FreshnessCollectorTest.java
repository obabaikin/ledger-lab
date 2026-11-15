package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.availability.AvailabilityRepository;
import com.ledgerlab.control.availability.FreshnessCollector;
import com.ledgerlab.control.metrics.BankMetrics;
import com.ledgerlab.control.metrics.PrometheusApi;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
class FreshnessCollectorTest extends IntegrationTest {

    private static final String FAST = BankMetrics.FAST_IN_MINUTE.formatted("2.0");
    private static final String STARTS = BankMetrics.PROCESS_STARTS.formatted("2s");
    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired FreshnessCollector collector;
    @Autowired AvailabilityRepository repository;
    @Autowired DatabaseClock clock;

    @BeforeEach
    void readStartsAfresh() {
        ReflectionTestUtils.setField(collector, "startsReadUpTo", null);
        starts(List.of());
    }

    private Instant minuteAgo(int minutes) {
        return clock.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(minutes));
    }

    private void kept(Instant minute) {
        jdbc.sql("INSERT INTO availability_minutes (minute, total, errors, busy) VALUES (:minute, 100, 0, 0)")
            .param("minute", Timestamp.from(minute))
            .update();
    }

    /** One value per minute, at the instant a minute after its start, as a range query at a step of a minute answers. */
    private void monitoring(String query, Map<Instant, String> valueByMinute) {
        var values = new ArrayList<List<Object>>();
        valueByMinute.forEach((minute, value) -> values.add(List.of(minute.plus(MINUTE).getEpochSecond(), value)));
        prometheus.answerQuery("GET", PrometheusApi.QUERY_RANGE_PATH, "query=" + query, 200,
            mapOf("status", "success", "data", mapOf("resultType", "matrix", "result", List.of(mapOf("metric", Map.of(), "values", values)))));
    }

    private void monitoring(Instant minute, String transfers, String fast) {
        monitoring(BankMetrics.TRANSFERS_IN_MINUTE, Map.of(minute, transfers));
        monitoring(FAST, Map.of(minute, fast));
    }

    /** Each series is a pod and the start times it told, every value at its own instant two seconds apart. */
    private void starts(List<Map<String, Object>> series) {
        prometheus.answerQuery("GET", PrometheusApi.QUERY_RANGE_PATH, "query=" + STARTS, 200,
            mapOf("status", "success", "data", mapOf("resultType", "matrix", "result", series)));
    }

    private Map<String, Object> pod(String instance, String... startTimes) {
        var values = new ArrayList<List<Object>>();
        var at = clock.now().getEpochSecond();
        for (var startTime : startTimes) {
            values.add(List.of(at, startTime));
            at += 2;
        }
        return mapOf("metric", Map.of("instance", instance), "values", values);
    }

    private List<Map<String, Object>> keptStarts() {
        return jdbc.sql("SELECT instance, started_at FROM service_starts ORDER BY instance, started_at").query().listOfRows();
    }

    private Map<String, Object> row(Instant minute) {
        return jdbc.sql("SELECT transfers, slow FROM availability_minutes WHERE minute = :minute")
            .param("minute", Timestamp.from(minute))
            .query()
            .singleRow();
    }

    private List<StubService.Call> rangeQueries() {
        return prometheus.callsTo("GET", PrometheusApi.QUERY_RANGE_PATH).stream().filter(it -> !STARTS.equals(it.params().get("query"))).toList();
    }

    private List<StubService.Call> startsQueries() {
        return prometheus.callsTo("GET", PrometheusApi.QUERY_RANGE_PATH).stream().filter(it -> STARTS.equals(it.params().get("query"))).toList();
    }

    @Test
    @DisplayName("a minute with 1000 transfers of which 985 were fast keeps 1000 transfers and 15 slow")
    void a_minute_with_1000_transfers_of_which_985_were_fast_keeps_1000_transfers_and_15_slow() throws Exception {
        var calm = minuteAgo(3);
        var allFast = minuteAgo(2);
        kept(calm);
        kept(allFast);
        monitoring(BankMetrics.TRANSFERS_IN_MINUTE, Map.of(calm, "1000.4", allFast, "10"));
        monitoring(FAST, Map.of(calm, "985", allFast, "10"));

        assertThat(collector.collect(clock.now())).isEqualTo(2);

        assertThat(row(calm)).isEqualTo(Map.of("transfers", 1000L, "slow", 15L));
        assertThat(row(allFast)).isEqualTo(Map.of("transfers", 10L, "slow", 0L));
        var asked = rangeQueries().getFirst().params();
        assertThat(asked.get("step")).isEqualTo("60");
        assertThat(asked.get("start")).isEqualTo(String.valueOf(calm.plus(MINUTE).getEpochSecond()));
    }

    @Test
    @DisplayName("a minute monitoring told about keeps its numbers when told again")
    void a_minute_monitoring_told_about_keeps_its_numbers_when_told_again() throws Exception {
        var minute = minuteAgo(3);
        kept(minute);
        monitoring(minute, "1000", "985");
        collector.collect(clock.now());

        assertThat(repository.fill(List.of(new AvailabilityRepository.FreshMinute(minute, 5, 5)))).isZero();

        assertThat(row(minute)).isEqualTo(Map.of("transfers", 1000L, "slow", 15L));
    }

    @Test
    @DisplayName("a minute older than an hour is not asked about any more; one 50 minutes old still is")
    void a_minute_older_than_an_hour_is_not_asked_about_any_more_one_50_minutes_old_still_is() throws Exception {
        var tooOld = minuteAgo(70);
        var old = minuteAgo(50);
        kept(tooOld);
        kept(old);
        monitoring(old, "1000", "1000");

        collector.collect(clock.now());

        assertThat(rangeQueries()).isNotEmpty().allSatisfy(it ->
            assertThat(it.params().get("start")).isEqualTo(String.valueOf(old.plus(MINUTE).getEpochSecond())));
        assertThat(row(old).get("transfers")).isEqualTo(1000L);
        assertThat(row(tooOld).get("transfers")).isNull();
    }

    @Test
    @DisplayName("a minute that ended 20 seconds ago is not asked about yet; 30 seconds after its end it is")
    void a_minute_that_ended_20_seconds_ago_is_not_asked_about_yet_30_seconds_after_its_end_it_is() throws Exception {
        var minute = minuteAgo(3);
        kept(minute);
        monitoring(minute, "1000", "1000");

        assertThat(collector.collect(minute.plus(MINUTE).plusSeconds(20))).isZero();
        assertThat(rangeQueries()).isEmpty();

        assertThat(collector.collect(minute.plus(MINUTE).plusSeconds(30))).isEqualTo(1);
        assertThat(rangeQueries().getFirst().params().get("end")).isEqualTo(String.valueOf(minute.plus(MINUTE).getEpochSecond()));
    }

    @Test
    @DisplayName("without Prometheus no minute is filled and the warning is logged once, not on every tick")
    void without_Prometheus_no_minute_is_filled_and_the_warning_is_logged_once_not_on_every_tick(CapturedOutput output) throws Exception {
        var minute = minuteAgo(3);
        kept(minute);
        ReflectionTestUtils.setField(collector, "lastAnswered", true);
        prometheus.status = 503;

        collector.collect(clock.now());
        collector.collect(clock.now());

        assertThat(row(minute).get("transfers")).isNull();
        assertThat(output.getOut().split("Freshness of the minutes read from Prometheus: false", -1)).hasSize(2);
        prometheus.status = 200;
        monitoring(minute, "1000", "1000");
        collector.collect(clock.now());
        assertThat(row(minute).get("transfers")).isEqualTo(1000L);
    }

    @Test
    @DisplayName("each pod's start is kept once, to the millisecond, however many times monitoring tells it")
    void each_pods_start_is_kept_once_to_the_millisecond_however_many_times_monitoring_tells_it() throws Exception {
        starts(List.of(pod("ledger-0", "1700000000.1234", "1700000000.1234", "1700000100.5"), pod("gateway-a", "1700000050")));

        collector.collect(clock.now());
        collector.collect(clock.now().plusSeconds(10));

        assertThat(keptStarts()).containsExactly(
            Map.of("instance", "gateway-a", "started_at", Timestamp.from(Instant.parse("2023-11-14T22:14:10Z"))),
            Map.of("instance", "ledger-0", "started_at", Timestamp.from(Instant.parse("2023-11-14T22:13:20.123Z"))),
            Map.of("instance", "ledger-0", "started_at", Timestamp.from(Instant.parse("2023-11-14T22:15:00.500Z"))));
        assertThat(startsQueries().getFirst().params().get("step")).isEqualTo("2");
    }

    @Test
    @DisplayName("the starts are read on from the last instant read, never ahead of now; after Prometheus was away, from that instant, at most an hour back")
    void the_starts_are_read_on_from_the_last_instant_read_never_ahead_of_now_after_Prometheus_was_away_from_that_instant_at_most_an_hour_back() throws Exception {
        var now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        collector.collect(now);
        var firstEnd = Long.parseLong(startsQueries().getFirst().params().get("end"));
        assertThat(Long.parseLong(startsQueries().getFirst().params().get("start"))).isEqualTo(now.minus(Duration.ofHours(1)).getEpochSecond());

        prometheus.reset();
        prometheus.status = 503;
        collector.collect(now.plusSeconds(30));
        prometheus.status = 200;
        starts(List.of());
        collector.collect(now.plusSeconds(60));

        assertThat(startsQueries()).extracting(it -> Long.parseLong(it.params().get("start"))).containsExactly(firstEnd + 2, firstEnd + 2);
        var secondEnd = Long.parseLong(startsQueries().getLast().params().get("end"));
        collector.collect(Instant.ofEpochSecond(secondEnd).plusSeconds(1));
        assertThat(startsQueries()).as("an instant not yet over is not read").hasSize(2);
        prometheus.reset();
        starts(List.of());
        collector.collect(now.plus(Duration.ofHours(3)));
        assertThat(Long.parseLong(startsQueries().getFirst().params().get("start"))).isEqualTo(now.plus(Duration.ofHours(2)).getEpochSecond());
    }
}
