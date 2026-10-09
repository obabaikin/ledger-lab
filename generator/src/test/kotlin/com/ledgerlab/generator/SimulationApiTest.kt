package com.ledgerlab.generator

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SimulationApiTest {

    companion object {
        private val gateway = FakeGateway()

        @JvmStatic
        @DynamicPropertySource
        fun gatewayUrl(registry: DynamicPropertyRegistry) {
            registry.add("generator.gateway-url") { gateway.url }
        }

        @JvmStatic
        @AfterAll
        fun shutdown() = gateway.close()
    }

    @Autowired lateinit var http: TestRestTemplate

    @Test
    fun `starts, reports and finishes a simulation`() {
        val runId = UUID.randomUUID()
        val request = mapOf("runId" to runId, "tps" to 50, "durationSeconds" to 1, "accountCount" to 10, "blockedAccounts" to 1)

        val started = http.postForEntity("/internal/simulations", request, Map::class.java)
        assertThat(started.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(started.body!!["state"]).isEqualTo("RUNNING")

        val again = http.postForEntity("/internal/simulations", request, Map::class.java)
        assertThat(again.statusCode).isEqualTo(HttpStatus.CONFLICT)

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            val status = http.getForEntity("/internal/simulations/$runId", Map::class.java).body!!
            assertThat(status["state"]).isEqualTo("FINISHED")
            assertThat(status["acknowledged"]).isEqualTo(status["requested"])
            assertThat((status["requested"] as Number).toInt()).isBetween(40, 60)
        }
    }

    @Test
    fun `a stop is accepted at once and completes in the background`() {
        val runId = UUID.randomUUID()
        val request = mapOf("runId" to runId, "tps" to 50, "durationSeconds" to 60, "accountCount" to 10)
        http.postForEntity("/internal/simulations", request, Map::class.java)

        val stopping = http.postForEntity("/internal/simulations/$runId/stop", null, Map::class.java)
        assertThat(stopping.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            val status = http.getForEntity("/internal/simulations/$runId", Map::class.java).body!!
            assertThat(status["state"]).isEqualTo("STOPPED")
            assertThat(status["inFlight"]).isEqualTo(0)
        }
    }

    @Test
    fun `a stop after the run has ended answers 200 with the final state`() {
        val runId = UUID.randomUUID()
        val request = mapOf("runId" to runId, "tps" to 20, "durationSeconds" to 1, "accountCount" to 10)
        http.postForEntity("/internal/simulations", request, Map::class.java)
        await().atMost(Duration.ofSeconds(15)).until {
            http.getForEntity("/internal/simulations/$runId", Map::class.java).body!!["state"] == "FINISHED"
        }

        val stop = http.postForEntity("/internal/simulations/$runId/stop", null, Map::class.java)

        assertThat(stop.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(stop.body!!["state"]).isEqualTo("FINISHED")
    }

    @Test
    fun `rejects an invalid request`() {
        val request = mapOf("runId" to UUID.randomUUID(), "tps" to 0, "durationSeconds" to 1, "accountCount" to 10)

        val response = http.postForEntity("/internal/simulations", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
