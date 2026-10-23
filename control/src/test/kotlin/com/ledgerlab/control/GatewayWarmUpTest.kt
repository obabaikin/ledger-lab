package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.prometheus
import com.ledgerlab.control.gateway.GatewayWarmUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class GatewayWarmUpTest : IntegrationTest() {

    @Autowired lateinit var warmUp: GatewayWarmUp

    private fun lowest(value: Int) =
        prometheus.answerQuery("GET", QUERY_PATH, "query=$ALL_WARM", 200, prometheus(listOf(emptyMap<String, String>() to value)))

    @Test
    fun `warm when every gateway instance finished its warm-up`() {
        lowest(1)

        assertThat(warmUp.check()).isTrue()
        assertThat(warmUp.warm).isTrue()
    }

    @Test
    fun `not warm while any instance still warms up`() {
        lowest(0)

        assertThat(warmUp.check()).isFalse()
    }

    @Test
    fun `not warm when no gateway instance is scraped`() {
        prometheus.answerQuery("GET", QUERY_PATH, "query=$ALL_WARM", 200, prometheus(emptyList()))

        assertThat(warmUp.check()).isFalse()
    }

    @Test
    fun `not warm when Prometheus cannot be read, even after it said warm`() {
        lowest(1)
        warmUp.check()

        prometheus.reset()
        prometheus.status = 503

        assertThat(warmUp.check()).isFalse()
        assertThat(warmUp.warm).isFalse()
    }

    private companion object {
        const val QUERY_PATH = "/api/v1/query"
        const val ALL_WARM = "min(gateway_warm_up_complete)"
    }
}
