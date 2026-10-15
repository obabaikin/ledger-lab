package com.ledgerlab.ledger

import com.ledgerlab.ledger.api.RunController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

@AutoConfigureTestRestTemplate
@TestPropertySource(
    properties = [
        "ledger.reconciliation.statement-timeout=500ms",
        // One pooled connection: the reconciliation and the check after it share it.
        "spring.datasource.hikari.maximum-pool-size=1",
        // The outbox poll reads transfers: on the test's lock it would wait holding that one connection.
        "ledger.results.publish-interval=1h",
    ],
)
class ReconciliationTimeoutTest : IntegrationTest() {

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var database: JdbcConnectionDetails

    @Test
    fun `a reconciliation stuck on a lock answers 503 and leaves no query behind`() {
        val runId = openRun()
        holdingTransfersLock { lockHolder ->
            val response = http.getForEntity("/internal/runs/$runId/reconciliation", Map::class.java)

            assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(response.body!!["error"]).isEqualTo(RunController.STATEMENT_TIMEOUT)
            assertThat(activeReconciliationQueries(lockHolder)).isZero()
        }
    }

    @Test
    fun `the statement timeout ends with the reconciliation's transaction`() {
        val runId = openRun()

        val response = http.getForEntity("/internal/runs/$runId/reconciliation", Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(jdbc.sql("SHOW statement_timeout").query(String::class.java).single()).isEqualTo(SERVER_DEFAULT)
    }

    private fun openRun(): UUID = UUID.randomUUID().also { runId ->
        val body = mapOf("accountCount" to 2, "openingBalanceCents" to 100, "blockedAccounts" to 0, "dailyLimitCents" to 100)
        assertThat(http.postForEntity("/internal/runs/$runId/accounts", body, List::class.java).statusCode)
            .isEqualTo(HttpStatus.CREATED)
    }

    private fun holdingTransfersLock(block: (Connection) -> Unit) {
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { lockHolder ->
            lockHolder.autoCommit = false
            lockHolder.createStatement().use {
                // Ends the lock even if the reconciliation never times out, so a regression fails instead of hanging.
                it.execute("SET idle_in_transaction_session_timeout = '10s'")
                it.execute("LOCK TABLE ledger.transfers IN ACCESS EXCLUSIVE MODE")
            }
            try {
                block(lockHolder)
            } finally {
                lockHolder.rollback()
            }
        }
    }

    private fun activeReconciliationQueries(connection: Connection): Int =
        connection.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query LIKE '%FROM transfers t%' AND pid <> pg_backend_pid()",
        ).use { statement -> statement.executeQuery().use { it.next(); it.getInt(1) } }

    private companion object {
        const val SERVER_DEFAULT = "0"
    }
}
