package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.gateway.api.TransferWarmUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"gateway.warm-up.rounds=20", "gateway.warm-up.duration=5s", "gateway.warm-up.max-duration=10s"})
@ExtendWith(OutputCaptureExtension.class)
class WarmUpDoneTest extends IntegrationTest {

    @Autowired TransferWarmUp warmUp;
    @Autowired MeterRegistry registry;

    @Test
    @DisplayName("a gateway that does all its rounds before the short limit is warm as it takes traffic, without a warning")
    void a_gateway_that_does_all_its_rounds_before_the_short_limit_is_warm_as_it_takes_traffic_without_a_warning(CapturedOutput output)
        throws InterruptedException {
        warmUp.warmUp();

        assertThat(registry.get(TransferWarmUp.WARM).gauge().value()).isEqualTo(1.0);
        assertThat(output.getOut()).contains("Warm-up: 20 rounds in").doesNotContain("reached its limit");
        // The name control queries in Prometheus before it admits a run.
        assertThat(http.getForObject("/actuator/prometheus", String.class))
            .containsPattern("(?m)^gateway_warm_up_complete 1");
    }
}
