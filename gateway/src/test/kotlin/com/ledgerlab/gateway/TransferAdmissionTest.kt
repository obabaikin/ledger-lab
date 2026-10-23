package com.ledgerlab.gateway

import com.ledgerlab.gateway.service.GatewayBusyException
import com.ledgerlab.gateway.service.TransferAdmission
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class TransferAdmissionTest {

    private val props = GatewayProperties(
        "transfers", "results", GatewayProperties.Outbox(100, Duration.ofSeconds(1)),
        GatewayProperties.Consumer(Duration.ofSeconds(1)), Duration.ofSeconds(10), Duration.ofSeconds(1),
        Duration.ofSeconds(5), GatewayProperties.Admission(1, Duration.ofMillis(10)),
        GatewayProperties.WarmUp(0, Duration.ZERO, Duration.ZERO),
    )

    @Test
    fun `a submission that throws gives its permit back`() {
        val admission = TransferAdmission(props, SimpleMeterRegistry())

        assertThatThrownBy { admission.admit { throw IllegalStateException("database down") } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(admission.admit { "taken" }).isEqualTo("taken")
    }

    @Test
    fun `with every permit held the next submission is refused as busy`() {
        val admission = TransferAdmission(props, SimpleMeterRegistry())

        admission.admit {
            assertThatThrownBy { admission.admit { "second" } }.isInstanceOf(GatewayBusyException::class.java)
        }
    }
}
