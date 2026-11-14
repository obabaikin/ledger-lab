package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.ACCEPTED;
import static com.ledgerlab.control.Fixtures.POSTED;
import static com.ledgerlab.control.Fixtures.POSTED_AMOUNT_CENTS;
import static com.ledgerlab.control.Fixtures.REJECTED;
import static com.ledgerlab.control.Fixtures.books;
import static com.ledgerlab.control.Fixtures.load;
import static com.ledgerlab.control.Fixtures.mapOf;
import static com.ledgerlab.control.Fixtures.prometheusVector;
import static com.ledgerlab.control.Fixtures.sample;
import static com.ledgerlab.control.Fixtures.stats;
import static com.ledgerlab.control.http.ServiceHttp.forPeriod;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.metrics.BankMetrics;
import com.ledgerlab.control.metrics.PrometheusApi;
import com.ledgerlab.control.periods.Period;
import com.ledgerlab.control.reporting.ReportingApi;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BankApiTest extends BankIntegrationTest {

    @Autowired DatabaseClock clock;

    private Map<String, Object> bank() {
        var answer = get("/api/bank");
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        return answer.getBody();
    }

    private List<?> periods() {
        return (List<?>) get("/api/bank/periods").getBody().get("periods");
    }

    private static List<Object> fieldOfEach(List<?> items, String field) {
        return items.stream().<Object>map(it -> ((Map<?, ?>) it).get(field)).toList();
    }

    private Period currentPeriod() {
        return Period.containing(clock.now(), LENGTH);
    }

    @Test
    @DisplayName("before control's first tick the bank has no start, no periods and no live numbers, and answers all the same")
    void before_controls_first_tick_the_bank_has_no_start_no_periods_and_no_live_numbers_and_answers_all_the_same() {
        var bank = bank();

        assertThat(bank.get("startedAt")).isNull();
        assertThat(bank.get("periodMinutes")).isEqualTo(60);
        assertThat(bank.get("totals")).isEqualTo(mapOf("accepted", 0, "posted", 0, "rejected", 0, "postedAmountCents", 0, "rejectionsByReason", Map.of()));
        assertThat(bank.get("clients")).isNull();
        assertThat(bank.get("pods")).isNull();
        assertThat(bank.get("books")).isNull();
        assertThat(bank.get("maintenance")).isNull();
        assertThat(bank.get("readAt")).isNull();
        assertThat(periods()).isEmpty();
    }

    @Test
    @DisplayName("a bank younger than a period lists no period and says when its first one is closed")
    void a_bank_younger_than_a_period_lists_no_period_and_says_when_its_first_one_is_closed() {
        conductor.tick();

        var current = currentPeriod();
        var bank = bank();
        assertThat(Instant.parse((String) bank.get("startedAt"))).isAfterOrEqualTo(current.start()).isBefore(current.end());
        assertThat(bank.get("currentPeriod")).isEqualTo(mapOf(
            "start", current.start().toString(), "end", current.end().toString(), "closesAt", current.end().plus(CLOSE_DELAY).toString()));
        assertThat(periods()).isEmpty();
        assertThat(get("/api/bank/periods/" + current.start()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("the totals add the live numbers of the open period to those of the periods that are final")
    void the_totals_add_the_live_numbers_of_the_open_period_to_those_of_the_periods_that_are_final() {
        bankStartedIn(PERIOD);
        bankAnswersFor(PERIOD);
        closeAndReconcile(PERIOD);
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, NEXT_PERIOD), 200, stats(NEXT_PERIOD, 50, 40, 5));

        conductor.tick(askTimeOf(PERIOD).plusSeconds(1));

        assertThat(bank().get("totals")).isEqualTo(mapOf(
            "accepted", (int) ACCEPTED + 50, "posted", (int) POSTED + 40, "rejected", (int) REJECTED + 5,
            "postedAmountCents", (int) (2 * POSTED_AMOUNT_CENTS), "rejectionsByReason", Map.of("INSUFFICIENT_FUNDS", 120, "BLOCKED_ACCOUNT", 80)));
        assertThat(bank().get("startedAt")).isEqualTo(PERIOD.plusSeconds(600).toString());
    }

    @Test
    @DisplayName("the bank started at control's first tick, not at the start of its period, and later ticks do not move it")
    void the_bank_started_at_controls_first_tick_not_at_the_start_of_its_period_and_later_ticks_do_not_move_it() {
        var firstTick = PERIOD.plusSeconds(37 * 60 + 12);
        conductor.tick(firstTick);

        conductor.tick(firstTick.plusSeconds(1200));

        assertThat(bank().get("startedAt")).isEqualTo(firstTick.toString());
    }

    @Test
    @DisplayName("the totals do not fall when a period fails: it keeps the numbers from its events")
    void the_totals_do_not_fall_when_a_period_fails_it_keeps_the_numbers_from_its_events() {
        bankStartedIn(PERIOD);
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, PERIOD), 200, stats(PERIOD));
        conductor.tick(PERIOD.plusSeconds(1200));
        var before = bank().get("totals");
        gateway.answer("POST", forPeriod(GatewayApi.CLOSE_PATH, PERIOD), 404, mapOf("error", "unknown-period"));
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, PERIOD), 503);

        conductor.tick(closingTimeOf(PERIOD));

        assertThat(stateOf(PERIOD)).isEqualTo("FAILED");
        assertThat(((Map<?, ?>) before).get("accepted")).isEqualTo((int) ACCEPTED);
        assertThat(bank().get("totals")).isEqualTo(before);
    }

    @Test
    @DisplayName("the bank shows what the clients count, the clients that gave up, the books and the instances")
    void the_bank_shows_what_the_clients_count_the_clients_that_gave_up_the_books_and_the_instances() {
        generator.answer("GET", GeneratorApi.LOAD_PATH, 200, load());
        ledger.answer("GET", LedgerApi.BOOKS_PATH, 200, books(true));
        prometheus.answerQuery("GET", PrometheusApi.QUERY_PATH, "query=" + BankMetrics.UP, 200, prometheusVector(
            sample(Map.of("job", "ledger", "instance", "ledger-0:8080"), 1), sample(Map.of("job", "gateway", "instance", "gateway-0:8081"), 0)));
        prometheus.answerQuery("GET", PrometheusApi.QUERY_PATH, "query=" + BankMetrics.ASSIGNED_PARTITIONS, 200, prometheusVector(
            sample(Map.of("job", "ledger", "instance", "ledger-0:8080"), 6)));

        conductor.tick();

        var bank = bank();
        assertThat(bank.get("clients")).isEqualTo(mapOf(
            "since", "2026-11-10T08:00:00Z", "baseTps", 400, "targetTps", 400, "pulse", null, "requested", 5000, "acknowledged", 4980,
            "gaveUp", 7, "refused", 6, "retries", 9, "inFlight", 13, "total", Fixtures.TOTAL, "restarted", true));
        var books = (Map<?, ?>) bank.get("books");
        assertThat(Arrays.asList(books.get("accounts"), books.get("balanced"))).isEqualTo(Arrays.asList((int) Fixtures.ACCOUNTS, true));
        assertThat(bank.get("pods")).isEqualTo(List.of(
            mapOf("job", "gateway", "instance", "gateway-0:8081", "up", false, "partitions", null),
            mapOf("job", "ledger", "instance", "ledger-0:8080", "up", true, "partitions", 6)));
        assertThat(bank.get("readAt")).isNotNull();
    }

    @Test
    @DisplayName("a part whose service stops answering keeps the last numbers read")
    void a_part_whose_service_stops_answering_keeps_the_last_numbers_read() {
        generator.answer("GET", GeneratorApi.LOAD_PATH, 200, load());
        conductor.tick();
        generator.answer("GET", GeneratorApi.LOAD_PATH, 503);

        conductor.tick();

        assertThat(((Map<?, ?>) bank().get("clients")).get("requested")).isEqualTo(5000);
    }

    @Test
    @DisplayName("the books are read once in a while, not on every tick")
    void the_books_are_read_once_in_a_while_not_on_every_tick() {
        ledger.answer("GET", LedgerApi.BOOKS_PATH, 200, books(true));
        var start = PERIOD.plusSeconds(600);

        conductor.tick(start);
        conductor.tick(start.plusSeconds(1));
        conductor.tick(start.plusSeconds(59));
        assertThat(ledger.callsTo("GET", LedgerApi.BOOKS_PATH)).hasSize(1);

        conductor.tick(start.plusSeconds(60));
        assertThat(ledger.callsTo("GET", LedgerApi.BOOKS_PATH)).hasSize(2);
    }

    @Test
    @DisplayName("the periods that are over are listed newest first, as many as asked for")
    void the_periods_that_are_over_are_listed_newest_first_as_many_as_asked_for() {
        bankStartedIn(PERIOD);
        List.of(PERIOD, NEXT_PERIOD).forEach(this::bankAnswersFor);
        closeAndReconcile(PERIOD);
        conductor.tick(closingTimeOf(NEXT_PERIOD));

        var listed = periods();

        assertThat(fieldOfEach(listed, "start")).containsExactly(NEXT_PERIOD.plus(LENGTH).toString(), NEXT_PERIOD.toString(), PERIOD.toString());
        assertThat(fieldOfEach(listed, "state")).containsExactly("OPEN", "DRAINING", "RECONCILED");
        assertThat(fieldOfEach(listed, "verdict")).containsExactly(null, null, "BALANCED");
        assertThat((List<?>) get("/api/bank/periods?limit=1").getBody().get("periods")).hasSize(1);
    }

    @Test
    @DisplayName("a limit outside 1 to 100, or one that is no number, is refused with its code")
    void a_limit_outside_1_to_100_or_one_that_is_no_number_is_refused_with_its_code() {
        List.of("0", "101", "-1", "ten").forEach(limit -> {
            var answer = get("/api/bank/periods?limit=" + limit);
            assertThat(answer.getStatusCode().value()).as(limit).isEqualTo(400);
            assertThat(answer.getBody().get("error")).as(limit).isEqualTo("invalid-limit");
        });
        assertThat(get("/api/bank/periods?limit=100").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a period the bank never had, or a start that is no instant, is not found")
    void a_period_the_bank_never_had_or_a_start_that_is_no_instant_is_not_found() {
        bankStartedIn(PERIOD);

        List.of(PERIOD.minus(LENGTH).toString(), PERIOD.plusSeconds(1).toString(), "yesterday").forEach(start -> {
            var answer = get("/api/bank/periods/" + start);
            assertThat(answer.getStatusCode().value()).as(start).isEqualTo(404);
            assertThat(answer.getBody().get("error")).as(start).isEqualTo("unknown-period");
        });
    }

    @Test
    @DisplayName("the bank shows the maintenance window that is on, else the next one, and none once it is cancelled")
    void the_bank_shows_the_maintenance_window_that_is_on_else_the_next_one_and_none_once_it_is_cancelled() {
        var now = clock.now();
        var from = Instant.parse("2099-11-12T01:00:00Z");
        window(now.minusSeconds(7200), now.minusSeconds(3600));
        window(from, from.plusSeconds(1800));
        window(from.plus(Duration.ofDays(7)), from.plus(Duration.ofDays(7)).plusSeconds(1800));

        assertThat(bank().get("maintenance")).isEqualTo(mapOf("from", from.toString(), "to", from.plusSeconds(1800).toString()));

        jdbc.sql("UPDATE maintenance_windows SET cancelled_at = now()").update();
        assertThat(bank().get("maintenance")).isNull();
    }

    private void window(Instant from, Instant to) {
        jdbc.sql("INSERT INTO maintenance_windows (starts_at, ends_at) VALUES (:from, :to)")
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .update();
    }

    @Test
    @DisplayName("the bank shows the budget of bad minutes and how much of it is used")
    void the_bank_shows_the_budget_of_bad_minutes_and_how_much_of_it_is_used() {
        jdbc.sql(
            """
            INSERT INTO availability_hours (hour, measured_minutes, bad_minutes, planned_minutes, total, errors, busy)
            VALUES (date_trunc('hour', now() - interval '2 days', 'UTC'), 60, 13, 5, 1000000, 0, 20000)
            """).update();

        assertThat(bank().get("slo")).isEqualTo(mapOf(
            "target", 0.999, "windowDays", 30, "measuredMinutes", 60, "badMinutes", 13, "budgetMinutes", 43, "plannedMinutes", 5,
            "availability", 42.0 / 55, "busyShare", 0.02));
    }

    @Test
    @DisplayName("every visitor sees a crash of the ledger and how long until the next one may be set off")
    void every_visitor_sees_a_crash_of_the_ledger_and_how_long_until_the_next_one_may_be_set_off() {
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (now() - interval '3 hours', ARRAY['ledger-0'])").update();
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (now() - interval '10 minutes', ARRAY['ledger-0', 'ledger-1'])").update();

        var chaos = (Map<?, ?>) bank().get("chaos");

        assertThat(chaos.get("available")).isEqualTo(false);
        assertThat(chaos.get("readyInSeconds")).isEqualTo(0);
        assertThat(fieldOfEach((List<?>) chaos.get("recent"), "instances")).containsExactly(List.of("ledger-0", "ledger-1"));
    }

    @Test
    @DisplayName("the charts put what monitoring answers on one grid, with nothing where monitoring has no value")
    void the_charts_put_what_monitoring_answers_on_one_grid_with_nothing_where_monitoring_has_no_value() {
        var step = 30;
        var third = Instant.now().getEpochSecond() / step * step - 600;
        prometheus.answer("GET", PrometheusApi.QUERY_RANGE_PATH, 200, mapOf("status", "success", "data", mapOf("resultType", "matrix", "result", List.of(
            mapOf("metric", Map.of(), "values", List.of(List.of(third, "400.5"), List.of(third + step, "NaN"), List.of(third + 2 * step, "410")))))));

        var charts = get("/api/bank/series?range=2h").getBody();

        var from = Instant.parse((String) charts.get("from")).getEpochSecond();
        var index = (int) ((third - from) / step);
        assertThat(charts.get("stepSeconds")).isEqualTo(step);
        assertThat(charts.get("points")).isEqualTo(241);
        var series = (List<?>) charts.get("series");
        assertThat(fieldOfEach(series, "name")).containsExactly(
            "ACCEPTED_PER_SECOND", "POSTED_PER_SECOND", "REJECTED_PER_SECOND", "BUSY_PER_SECOND", "BANK_LATENCY_P50_MS", "BANK_LATENCY_P99_MS");
        var values = (List<?>) ((Map<?, ?>) series.getFirst()).get("values");
        assertThat(values).hasSize(241);
        assertThat(values.subList(index - 1, index + 4)).isEqualTo(Arrays.asList(null, 400.5, null, 410.0, null));
    }

    @Test
    @DisplayName("without monitoring the charts are empty and the bank answers as before")
    void without_monitoring_the_charts_are_empty_and_the_bank_answers_as_before() {
        prometheus.status = 503;

        var charts = get("/api/bank/series");

        assertThat(charts.getStatusCode().value()).isEqualTo(200);
        assertThat(charts.getBody().get("series")).isEqualTo(List.of());
        assertThat(bank().get("pods")).isNull();
    }

    @Test
    @DisplayName("a range other than the one served is refused with its code")
    void a_range_other_than_the_one_served_is_refused_with_its_code() {
        List.of("24h", "soon").forEach(range -> {
            var answer = get("/api/bank/series?range=" + range);
            assertThat(answer.getStatusCode().value()).as(range).isEqualTo(400);
            assertThat(answer.getBody().get("error")).as(range).isEqualTo("invalid-range");
        });
    }
}
