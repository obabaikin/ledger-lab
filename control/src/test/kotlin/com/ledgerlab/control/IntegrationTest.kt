package com.ledgerlab.control

import com.ledgerlab.control.runs.QueueScheduler
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTest.Containers::class)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {
        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")
    }

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var props: ControlProperties
    @Autowired lateinit var queueScheduler: QueueScheduler

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("TRUNCATE runs, sessions, invite_visits, invite_mails, invite_requests, invite_emails, invite_tokens, chaos_events").update()
        ledger.reset()
        gateway.reset()
        generator.reset()
        reporting.reset()
        prometheus.reset()
        turnstile.reset()
        mailjet.reset()
        ledgerInstances.forEach { it.reset() }
        // The scheduler keeps its last answer for the runs' views; a tick with nothing to read clears what an earlier test left.
        queueScheduler.tick()
    }

    protected fun invite(quota: Int = 100, expiresIn: Duration = Duration.ofDays(1), active: Boolean = true): String {
        val token = "invite-${UUID.randomUUID()}"
        jdbc.sql(
            """
            INSERT INTO invite_tokens (token, expires_at, quota, active)
            VALUES (:token, now() + CAST(:millis AS bigint) * interval '1 millisecond', :quota, :active)
            """,
        )
            .param("token", token)
            .param("millis", expiresIn.toMillis())
            .param("quota", quota)
            .param("active", active)
            .update()
        return token
    }

    protected fun enter(token: String?): ResponseEntity<Map<*, *>> =
        http.postForEntity("/api/sessions", mapOf("token" to token), Map::class.java)

    protected fun sessionOf(token: String): UUID = UUID.fromString(enter(token).body!!["sessionId"] as String)

    protected fun start(
        sessionId: Any,
        requestId: UUID = UUID.randomUUID(),
        tps: Int? = 100,
        durationSeconds: Int? = 10,
    ): ResponseEntity<Map<*, *>> =
        http.postForEntity(
            "/api/sessions/$sessionId/runs",
            mapOf("requestId" to requestId, "tps" to tps, "durationSeconds" to durationSeconds),
            Map::class.java,
        )

    protected fun getRun(sessionId: UUID, runId: Any?): ResponseEntity<Map<*, *>> =
        http.getForEntity("/api/sessions/$sessionId/runs/$runId", Map::class.java)

    protected fun stateOf(runId: Any?): String =
        jdbc.sql("SELECT state FROM runs WHERE run_id = :runId").param("runId", UUID.fromString(runId.toString())).query(String::class.java).single()

    protected fun setRun(runId: Any?, state: String? = null, createdAgo: Duration? = null) {
        state?.let {
            jdbc.sql("UPDATE runs SET state = :state WHERE run_id = :runId").param("state", it).param("runId", UUID.fromString(runId.toString())).update()
        }
        createdAgo?.let {
            jdbc.sql("UPDATE runs SET created_at = clock_timestamp() - CAST(:millis AS bigint) * interval '1 millisecond' WHERE run_id = :runId")
                .param("millis", it.toMillis())
                .param("runId", UUID.fromString(runId.toString()))
                .update()
        }
    }

    companion object {
        val ledger = StubService()
        val gateway = StubService()
        val generator = StubService()
        val reporting = StubService()
        val prometheus = StubService()
        val turnstile = StubService()
        val mailjet = StubService()
        val ledgerInstances = List(3) { StubService() }

        @JvmStatic
        @DynamicPropertySource
        fun serviceUrls(registry: DynamicPropertyRegistry) {
            registry.add("control.ledger.base-url") { ledger.baseUrl }
            registry.add("control.gateway.base-url") { gateway.baseUrl }
            registry.add("control.generator.base-url") { generator.baseUrl }
            registry.add("control.reporting.base-url") { reporting.baseUrl }
            registry.add("stub.prometheus-url") { prometheus.baseUrl }
            registry.add("control.turnstile.base-url") { turnstile.baseUrl }
            registry.add("control.mail.base-url") { mailjet.baseUrl }
            ledgerInstances.forEachIndexed { i, stub ->
                registry.add("control.chaos.ledger-instances[$i].name") { "ledger-$i" }
                registry.add("control.chaos.ledger-instances[$i].url") { stub.baseUrl }
            }
        }
    }
}
