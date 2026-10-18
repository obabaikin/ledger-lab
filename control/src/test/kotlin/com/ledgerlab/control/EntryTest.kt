package com.ledgerlab.control

import com.ledgerlab.control.api.ControlErrors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID

class EntryTest : IntegrationTest() {

    private fun visits(token: String): Long? =
        jdbc.sql("SELECT count FROM invite_visits WHERE token = :token").param("token", token).query(Long::class.java).optional().orElse(null)

    @Test
    fun `an invite opens a session and counts the visit`() {
        val token = invite()

        val response = enter(token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val sessionId = response.body!!["sessionId"] as String
        assertThat(response.headers.location.toString()).isEqualTo("/api/sessions/$sessionId")
        assertThat(visits(token)).isEqualTo(1)
    }

    @Test
    fun `every opening of the link is a new session and one more visit`() {
        val token = invite()

        val first = sessionOf(token)
        val second = sessionOf(token)

        assertThat(first).isNotEqualTo(second)
        assertThat(visits(token)).isEqualTo(2)
    }

    @Test
    fun `an unknown token gets 403 and leaves no visit`() {
        val response = enter("no-such-invite")

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.INVITE_NOT_VALID)
        assertThat(jdbc.sql("SELECT count(*) FROM invite_visits").query(Long::class.java).single()).isZero()
    }

    @Test
    fun `a missing or blank token gets 403`() {
        assertThat(enter(null).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(enter(" ").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `an expired or inactive invite gets 403 and no session, but its visit is counted`() {
        val expired = invite(expiresIn = Duration.ofMinutes(-1))
        val inactive = invite(active = false)

        listOf(expired, inactive).forEach { token ->
            val response = enter(token)
            assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
            assertThat(response.body!!["error"]).isEqualTo(ControlErrors.INVITE_NOT_VALID)
            assertThat(visits(token)).isEqualTo(1)
        }
        assertThat(jdbc.sql("SELECT count(*) FROM sessions").query(Long::class.java).single()).isZero()
    }

    @Test
    fun `a session shows its quota, the runs of its invite in the last hour and the options`() {
        val token = invite(quota = 5)
        val sessionId = sessionOf(token)
        start(sessionOf(token))
        start(sessionId)

        val view = http.getForEntity("/api/sessions/$sessionId", Map::class.java).body!!

        assertThat(view["sessionId"]).isEqualTo(sessionId.toString())
        assertThat(view["inviteValid"]).isEqualTo(true)
        assertThat(view["runsPerHour"]).isEqualTo(5)
        assertThat(view["runsLastHour"]).isEqualTo(2)
        assertThat(view["tpsOptions"]).isEqualTo(listOf(100, 500, 1000))
        assertThat(view["defaultTps"]).isEqualTo(1000)
        assertThat(view["durationOptions"]).isEqualTo(listOf(10, 20, 30))
    }

    @Test
    fun `a session of an invite switched off later still opens, as not valid`() {
        val token = invite()
        val sessionId = sessionOf(token)
        jdbc.sql("UPDATE invite_tokens SET active = false WHERE token = :token").param("token", token).update()

        val view = http.getForEntity("/api/sessions/$sessionId", Map::class.java)

        assertThat(view.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(view.body!!["inviteValid"]).isEqualTo(false)
    }

    @Test
    fun `an unknown session is 404`() {
        val response = http.getForEntity("/api/sessions/${UUID.randomUUID()}", Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo(ControlErrors.SESSION_NOT_FOUND)
    }
}
