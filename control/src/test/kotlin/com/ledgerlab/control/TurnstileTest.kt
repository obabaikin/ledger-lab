package com.ledgerlab.control

import com.ledgerlab.control.api.ControlErrors
import com.ledgerlab.control.entry.TurnstileClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import java.time.Duration

@TestPropertySource(properties = ["control.turnstile.secret=$SECRET", "control.turnstile.site-key=$SITE_KEY", "control.turnstile.request-timeout=500ms"])
class TurnstileTest : IntegrationTest() {

    private fun verdict(success: Boolean) = turnstile.answer("POST", TurnstileClient.SITEVERIFY_PATH, 200, mapOf("success" to success, "error-codes" to emptyList<String>()))

    private fun nothingRecorded() =
        assertThat(jdbc.sql("SELECT count(*) FROM invite_requests").query(Long::class.java).single()).isZero()

    @Test
    fun `a passed check lets the request through, with the secret and the widget's answer`() {
        verdict(success = true)

        assertThat(requestLink("visitor@example.org", "widget-answer").statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val call = objectMapper.readTree(turnstile.callsTo("POST", TurnstileClient.SITEVERIFY_PATH).single().body)
        assertThat(call.get("secret").asString()).isEqualTo(SECRET)
        assertThat(call.get("response").asString()).isEqualTo("widget-answer")
    }

    @Test
    fun `a failed or missing check is 403 and nothing is recorded`() {
        verdict(success = false)

        listOf("widget-answer", null).forEach { answer ->
            val response = requestLink("visitor@example.org", answer)
            assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
            assertThat(objectMapper.readTree(response.body).get("error").asString()).isEqualTo(ControlErrors.VERIFICATION_FAILED)
        }
        nothingRecorded()
    }

    @Test
    fun `a verifier that fails or does not answer is 503 busy and nothing is recorded`() {
        turnstile.status = 500
        val failed = requestLink("visitor@example.org", "widget-answer")
        turnstile.reset()
        turnstile.answer("POST", TurnstileClient.SITEVERIFY_PATH, 200, mapOf("success" to true), delay = Duration.ofSeconds(1))
        val silent = requestLink("visitor@example.org", "widget-answer")

        listOf(failed, silent).forEach { response ->
            assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(objectMapper.readTree(response.body).get("error").asString()).isEqualTo(ControlErrors.BUSY)
        }
        nothingRecorded()
    }

    @Test
    fun `the page gets the widget's site key`() {
        assertThat(http.getForEntity("/api/site", Map::class.java).body!!["turnstileSiteKey"]).isEqualTo(SITE_KEY)
    }
}

private const val SECRET = "turnstile-test-secret"
private const val SITE_KEY = "turnstile-test-site-key"
