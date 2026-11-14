package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerlab.control.availability.AvailabilityRepository;
import com.ledgerlab.control.bank.BankLive;
import com.ledgerlab.control.chaos.ChaosRepository;
import com.ledgerlab.control.gateway.GatewayClient;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.periods.PeriodConductor;
import com.ledgerlab.control.periods.PeriodRepository;
import com.ledgerlab.control.reporting.ReportingClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PeriodConductorFailureTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:01:00Z");

    private static ControlProperties props() {
        var second = Duration.ofSeconds(1);
        return new ControlProperties(
            new ControlProperties.Periods(
                Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(5),
                Duration.ofMinutes(40), Duration.ofHours(4)),
            null, null, null, null, null, null, null, null, null, new ControlProperties.Reporting("http://localhost", second),
            null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("a checked exception in a period's step is logged and the tick goes on to the live numbers")
    void a_checked_exception_in_a_periods_step_is_logged_and_the_tick_goes_on_to_the_live_numbers(CapturedOutput output) throws Exception {
        var periods = mock(PeriodRepository.class);
        var live = mock(BankLive.class);
        when(periods.openUpTo(any(), any(), any())).thenAnswer(invocation -> {
            throw new TimeoutException("a checked exception from below");
        });
        var conductor = new PeriodConductor(
            periods, mock(LedgerClient.class), mock(GatewayClient.class), mock(GeneratorClient.class), mock(ReportingClient.class), live,
            mock(ChaosRepository.class), mock(AvailabilityRepository.class), mock(DatabaseClock.class), props(), new SimpleMeterRegistry());

        conductor.tick(NOW);

        assertThat(output.getOut()).contains("step failed, retried on the next tick").contains("a checked exception from below");
        verify(live).refresh(any(), any());
    }
}
