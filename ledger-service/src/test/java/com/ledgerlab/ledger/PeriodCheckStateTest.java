package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ledgerlab.ledger.api.PeriodController;
import com.ledgerlab.ledger.persistence.PeriodRepository;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.PeriodChecker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@AutoConfigureTestRestTemplate
class PeriodCheckStateTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired LedgerService ledger;
    @Autowired MeterRegistry registry;
    @MockitoSpyBean PeriodRepository spiedPeriods;

    private static String checkOf(Instant periodStart) {
        return "/internal/periods/" + periodStart + "/check";
    }

    private long checksTimed() {
        return registry.get(PeriodChecker.DURATION_METRIC).timer().count();
    }

    @Test
    void asks_whether_the_check_is_made_without_counting_the_periods_transfers_and_shows_it_with_its_times_once_made() {
        openBank(3, 1_000);
        applyOne(ledger, transfer(1, 2, 100));
        var timed = checksTimed();
        clearInvocations(spiedPeriods);

        var before = http.getForEntity(checkOf(period.start()), Map.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(before.getBody().containsKey("check")).isTrue();
        assertThat(before.getBody().get("check")).isNull();

        http.postForEntity(checkOf(period.start()), null, Void.class);
        var check = await().atMost(Duration.ofSeconds(10))
            .until(() -> (Map<?, ?>) http.getForObject(checkOf(period.start()), Map.class).get("check"), it -> it != null);

        assertThat(check.get("balanced")).isEqualTo(true);
        assertThat(check.get("journalEntries")).isEqualTo(2);
        var startedAt = Instant.parse((String) check.get("startedAt"));
        var madeAt = Instant.parse((String) check.get("madeAt"));
        assertThat(madeAt).isAfterOrEqualTo(startedAt);
        assertThat(startedAt).isAfter(period.end());
        assertThat(checksTimed()).isEqualTo(timed + 1);
        verify(spiedPeriods, never()).countTransfers(any());
    }

    @Test
    void a_period_that_has_not_ended_is_off_the_grid_or_is_older_than_the_ledger_keeps_is_unknown() {
        var current = period.next().start();
        var offTheGrid = period.start().plusSeconds(1);
        var dropped = period.start().minus(periodsProperties.retention());

        for (var start : List.of(current, offTheGrid, dropped)) {
            var asked = http.getForEntity(checkOf(start), Map.class);
            assertThat(asked.getStatusCode()).as("check of %s", start).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(asked.getBody().get("error")).isEqualTo(PeriodController.UNKNOWN_PERIOD);
        }
    }
}
