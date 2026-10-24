package com.ledgerlab.gateway

import com.ledgerlab.gateway.api.TransferWarmUp
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["gateway.warm-up.rounds=20", "gateway.warm-up.duration=5s", "gateway.warm-up.max-duration=10s"])
@ExtendWith(OutputCaptureExtension::class)
class WarmUpDoneTest : IntegrationTest() {

    @Autowired lateinit var warmUp: TransferWarmUp
    @Autowired lateinit var registry: MeterRegistry

    @Test
    fun `a gateway that does all its rounds before the short limit is warm as it takes traffic, without a warning`(output: CapturedOutput) {
        warmUp.warmUp()

        assertThat(registry.get(TransferWarmUp.WARM).gauge().value()).isEqualTo(1.0)
        assertThat(output.out).contains("Warm-up: 20 rounds in").doesNotContain("reached its limit")
        // The name control queries in Prometheus before it admits a run.
        assertThat(http.getForObject("/actuator/prometheus", String::class.java))
            .containsPattern("(?m)^gateway_warm_up_complete 1")
    }
}
