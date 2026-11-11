package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.books;
import static com.ledgerlab.control.Fixtures.clientReconciliation;
import static com.ledgerlab.control.Fixtures.closed;
import static com.ledgerlab.control.Fixtures.reconciliation;
import static com.ledgerlab.control.Fixtures.stats;
import static com.ledgerlab.control.Fixtures.summary;
import static com.ledgerlab.control.http.ServiceHttp.forPeriod;

import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.reporting.ReportingApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;

/** The tests pass the conductor the time of each tick, so a period's hour takes them no time. */
abstract class BankIntegrationTest extends IntegrationTest {

    protected static final Duration LENGTH = Duration.ofHours(1);
    /** A period that is over by the database's clock too, as the API reads it. */
    protected static final Instant PERIOD = Instant.parse("2026-11-10T11:00:00Z");
    protected static final Instant NEXT_PERIOD = PERIOD.plus(LENGTH);
    protected static final Duration CLOSE_DELAY = Duration.ofSeconds(60);

    protected static Instant closingTimeOf(Instant period) {
        return period.plus(LENGTH).plus(CLOSE_DELAY);
    }

    /** The bank starts in the period: control opens the one its first tick falls in. */
    protected void bankStartedIn(Instant period) {
        conductor.tick(period.plusSeconds(600));
    }

    protected void bankAnswersFor(Instant period) {
        gateway.answer("POST", forPeriod(GatewayApi.CLOSE_PATH, period), 200, closed(period, Fixtures.ACCEPTED));
        gateway.answer("GET", forPeriod(GatewayApi.SUMMARY_PATH, period), 200, summary(period));
        ledger.answer("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, period), 200, reconciliation(period));
        ledger.answer("GET", LedgerApi.BOOKS_PATH, 200, books(true));
        generator.answer("GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, period), 200, clientReconciliation(period, "DONE"));
        reporting.answer("GET", forPeriod(ReportingApi.STATS_PATH, period), 200, stats(period));
    }

    protected void closeAndReconcile(Instant period) {
        conductor.tick(closingTimeOf(period));
        conductor.tick(closingTimeOf(period).plusSeconds(1));
    }

    /** A new instance of control knows nothing of the period the one before it was settling. */
    protected void controlRestarts() {
        ReflectionTestUtils.setField(conductor, "settling", null);
    }

    protected String stateOf(Instant period) {
        return jdbc.sql("SELECT state FROM periods WHERE period_start = :start").param("start", java.sql.Timestamp.from(period)).query(String.class).single();
    }

    protected Map<String, Object> periodFromApi(Instant period) {
        return get("/api/bank/periods/" + period).getBody();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> reportOf(Instant period) {
        return (Map<String, Object>) periodFromApi(period).get("report");
    }

    protected List<String> periodsInOrder() {
        return jdbc.sql("SELECT state FROM periods ORDER BY period_start").query(String.class).list();
    }
}
