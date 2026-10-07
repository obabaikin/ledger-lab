package com.ledgerlab.ledger

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpStatus
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@AutoConfigureTestRestTemplate
class RunControllerTest : IntegrationTest() {

    @Autowired lateinit var http: TestRestTemplate

    @Test
    fun `opens accounts once, reconciles and deletes a run`() {
        val runId = UUID.randomUUID()
        val body = mapOf("accountCount" to 5, "openingBalanceCents" to 1_000, "blockedAccounts" to 1, "dailyLimitCents" to 500)

        val created = http.postForEntity("/internal/runs/$runId/accounts", body, List::class.java)
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(created.body).hasSize(5)

        val again = http.postForEntity("/internal/runs/$runId/accounts", body, Map::class.java)
        assertThat(again.statusCode).isEqualTo(HttpStatus.CONFLICT)

        val report = http.getForEntity("/internal/runs/$runId/reconciliation", Map::class.java)
        assertThat(report.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(report.body!!["balanced"]).isEqualTo(true)
        assertThat(report.body!!["accounts"]).isEqualTo(5)

        http.delete("/internal/runs/$runId")
        val afterDelete = http.getForEntity("/internal/runs/$runId/accounts", List::class.java)
        assertThat(afterDelete.body).isEmpty()
    }

    @Test
    fun `validates the account setup`() {
        val runId = UUID.randomUUID()
        val body = mapOf("accountCount" to 1, "openingBalanceCents" to -5, "blockedAccounts" to 0, "dailyLimitCents" to 0)

        val response = http.postForEntity("/internal/runs/$runId/accounts", body, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects a setup that would block every account`() {
        val runId = UUID.randomUUID()
        val body = mapOf("accountCount" to 5, "openingBalanceCents" to 100, "blockedAccounts" to 5, "dailyLimitCents" to 100)

        val response = http.postForEntity("/internal/runs/$runId/accounts", body, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["error"].toString()).contains("blockedAccounts")
    }

    @Test
    fun `concurrent requests to open the same run yield one 201 and the rest 409`() {
        val runId = UUID.randomUUID()
        val body = mapOf("accountCount" to 50, "openingBalanceCents" to 100, "blockedAccounts" to 0, "dailyLimitCents" to 100)
        val pool = Executors.newFixedThreadPool(8)

        val statuses = try {
            pool.invokeAll(List(8) { Callable { http.postForEntity("/internal/runs/$runId/accounts", body, String::class.java).statusCode } })
                .map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertThat(statuses.count { it == HttpStatus.CREATED }).isEqualTo(1)
        assertThat(statuses.count { it == HttpStatus.CONFLICT }).isEqualTo(7)
    }
}
