package com.ledgerlab.control

import com.ledgerlab.control.api.ControlErrors
import com.ledgerlab.control.entry.AddressCleanup
import com.ledgerlab.control.entry.MailSender
import com.ledgerlab.control.entry.MailjetClient
import com.ledgerlab.control.entry.TurnstileClient
import com.ledgerlab.control.entry.minuteAfter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class InviteRequestTest : IntegrationTest() {

    @Autowired lateinit var sender: MailSender
    @Autowired lateinit var cleanup: AddressCleanup

    private fun address() = "visitor-${UUID.randomUUID()}@example.org"

    private fun kinds(email: String): List<String> =
        jdbc.sql("SELECT kind FROM invite_mails WHERE email = :email ORDER BY mail_id").param("email", email).query { rs, _ -> rs.getString(1) }.list()

    private fun tokens(email: String): List<String> =
        jdbc.sql("SELECT token FROM invite_mails WHERE email = :email AND kind = 'LINK' ORDER BY mail_id").param("email", email).query { rs, _ -> rs.getString(1) }.list()

    private fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.java).single()

    private fun ageMails(email: String, by: Duration) {
        jdbc.sql("UPDATE invite_mails SET requested_at = requested_at - CAST(:millis AS bigint) * interval '1 millisecond' WHERE email = :email")
            .param("millis", by.toMillis()).param("email", email).update()
    }

    private fun mailedSends() = mailjet.callsTo("POST", MailjetClient.SEND_PATH)

    @Test
    fun `a new, a known and an exhausted address get the same answer`() {
        val known = address()
        repeat(5) { requestLink(known) }

        listOf(address(), known).forEach { email ->
            val response = requestLink(email)
            assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
            assertThat(response.body).isEqualTo("{}")
        }
    }

    @Test
    fun `three requests mail the link, the fourth the limit and the fifth nothing`() {
        val email = address()

        repeat(6) { requestLink(email) }

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT")
        assertThat(count("SELECT count(*) FROM invite_requests")).isEqualTo(6)
    }

    @Test
    fun `mails older than the window do not count`() {
        val email = address()
        repeat(4) { requestLink(email) }
        ageMails(email, Duration.ofHours(24).plusMinutes(1))

        requestLink(email)

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT", "LINK")
    }

    @Test
    fun `the limit mail names when the oldest link leaves the window`() {
        val email = address()
        requestLink(email)
        ageMails(email, Duration.ofHours(5))
        repeat(3) { requestLink(email) }
        val oldestLink = jdbc.sql("SELECT min(requested_at) FROM invite_mails WHERE email = :email").param("email", email)
            .query(java.sql.Timestamp::class.java).single().toInstant()

        val freesAt = jdbc.sql("SELECT window_frees_at FROM invite_mails WHERE kind = 'LIMIT'").query(java.sql.Timestamp::class.java).single().toInstant()
        sender.sendDue()

        assertThat(freesAt).isEqualTo(oldestLink + Duration.ofHours(24))
        val limitMail = mailedSends().last().body
        assertThat(limitMail).contains("Ask again after ${UTC_MINUTES.format(minuteAfter(freesAt))} UTC.")
    }

    @Test
    fun `an active invite is mailed again, not replaced`() {
        val email = address()

        repeat(3) { requestLink(email) }

        assertThat(tokens(email).distinct()).hasSize(1)
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(1)
    }

    @Test
    fun `asking again while the link works extends it by the lifetime`() {
        val email = address()
        requestLink(email)
        val token = tokens(email).single()
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() + interval '2 hours' WHERE token = :token").param("token", token).update()

        requestLink(email)

        val left = jdbc.sql("SELECT CAST(extract(epoch FROM expires_at - clock_timestamp()) AS bigint) FROM invite_tokens WHERE token = :token")
            .param("token", token).query(Long::class.java).single()
        assertThat(tokens(email)).containsOnly(token)
        assertThat(left).isBetween(props.invites.email.lifetime.toSeconds() - 60, props.invites.email.lifetime.toSeconds())
    }

    @Test
    fun `a mail from before midnight but within the window still counts`() {
        val email = address()
        repeat(3) { requestLink(email) }
        jdbc.sql("UPDATE invite_mails SET requested_at = date_trunc('day', clock_timestamp()) - interval '1 second' WHERE email = :email")
            .param("email", email).update()

        requestLink(email)

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT")
    }

    @Test
    fun `an expired invite gets a new one`() {
        val email = address()
        requestLink(email)
        val first = tokens(email).single()
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute' WHERE token = :token").param("token", first).update()

        requestLink(email)

        assertThat(tokens(email)).hasSize(2).doesNotHaveDuplicates()
        assertThat(jdbc.sql("SELECT token FROM invite_emails WHERE email = :email").param("email", email).query(String::class.java).single())
            .isEqualTo(tokens(email).last())
    }

    @Test
    fun `a mailed invite has the configured lifetime and run quota`() {
        val email = address()
        requestLink(email)

        val (quota, lifetime) = jdbc.sql("SELECT quota, extract(epoch FROM expires_at - created_at) AS seconds FROM invite_tokens")
            .query { rs, _ -> rs.getInt("quota") to rs.getLong("seconds") }.single()

        assertThat(quota).isEqualTo(props.invites.email.runsPerHour)
        assertThat(lifetime).isEqualTo(props.invites.email.lifetime.toSeconds())
        assertThat(tokens(email).single()).startsWith("mail-")
    }

    @Test
    fun `asking again does not reset the run quota`() {
        val email = address()
        requestLink(email)
        start(sessionOf(tokens(email).single()))

        requestLink(email)
        val sessionId = sessionOf(tokens(email).last())

        val view = http.getForEntity("/api/sessions/$sessionId", Map::class.java).body!!
        assertThat(view["runsLastHour"]).isEqualTo(1)
    }

    @Test
    fun `concurrent requests for one address stay within the limits`() {
        val email = address()
        val ready = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            repeat(CONCURRENT) { pool.submit { ready.await(); requestLink(email) } }
            ready.countDown()
        }

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT")
        assertThat(count("SELECT count(*) FROM invite_emails")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM invite_requests")).isEqualTo(CONCURRENT.toLong())
    }

    @Test
    fun `over the daily limit of all mails is 429 with Retry-After, and nothing is recorded`() {
        val exhausted = address()
        repeat(5) { requestLink(exhausted) }
        repeat(props.invites.email.dailyMails - 4) { requestLink(address()) }
        val requestsBefore = count("SELECT count(*) FROM invite_requests")

        val refused = listOf(address(), exhausted).map { requestLink(it) }

        refused.forEach { response ->
            assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            assertThat(objectMapper.readTree(response.body).get("error").asString()).isEqualTo(ControlErrors.DAILY_LIMIT)
            assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)!!.toLong()).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds())
        }
        assertThat(count("SELECT count(*) FROM invite_requests")).isEqualTo(requestsBefore)
        assertThat(count("SELECT count(*) FROM invite_mails")).isEqualTo(props.invites.email.dailyMails.toLong())
    }

    @Test
    fun `an address is trimmed and lowercased`() {
        val email = address()

        requestLink("  ${email.uppercase()} ")
        requestLink(email)

        assertThat(kinds(email)).containsExactly("LINK", "LINK")
        assertThat(count("SELECT count(*) FROM invite_emails")).isEqualTo(1)
    }

    @Test
    fun `an empty, malformed or too long address is 400 and nothing is recorded`() {
        val tooLong = "a".repeat(255 - "@example.org".length) + "@example.org"

        listOf("", "   ", "no-at-sign", "two@at@signs.org", "with space@example.org", "a,b@example.org", tooLong).forEach { email ->
            val response = requestLink(email)
            assertThat(response.statusCode).describedAs(email).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(objectMapper.readTree(response.body).get("error").asString()).isEqualTo("invalid-email")
        }
        assertThat(requestLink(null).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val unreadable = http.postForEntity("/api/invite-requests", HttpEntity("{", jsonHeaders()), String::class.java)
        assertThat(unreadable.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(count("SELECT count(*) FROM invite_requests")).isZero()
        assertThat(requestLink(tooLong.drop(1)).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `without a Turnstile secret the check is skipped`() {
        assertThat(requestLink(address(), turnstileToken = null).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(turnstile.callsTo("POST", TurnstileClient.SITEVERIFY_PATH)).isEmpty()
    }

    @Test
    fun `a mail is sent once and marked sent`() {
        val email = address()
        requestLink(email)

        assertThat(sender.sendDue()).isEqualTo(1)
        assertThat(sender.sendDue()).isZero()

        val body = objectMapper.readTree(mailedSends().single().body).get("Messages").get(0)
        assertThat(body.get("To").get(0).get("Email").asString()).isEqualTo(email)
        assertThat(body.get("TextPart").asString()).contains("https://ledger-lab.test/?ref=${tokens(email).single()}")
        assertThat(body.get("TextPart").asString()).contains("The link works for 7 days. Your address is deleted after 30 days without use.")
        assertThat(count("SELECT count(*) FROM invite_mails WHERE sent_at IS NOT NULL")).isEqualTo(1)
    }

    @Test
    fun `link mail reads as before`() {
        val email = address()
        requestLink(email)

        sender.sendDue()

        val message = objectMapper.readTree(mailedSends().single().body).get("Messages").get(0)
        assertThat(message.get("Subject").asString()).isEqualTo("Your Ledger Lab link")
        assertThat(message.get("TextPart").asString()).isEqualTo(
            "Here is your link to Ledger Lab:\n\nhttps://ledger-lab.test/?ref=${tokens(email).single()}\n\n" +
                "The link works for 7 days. Your address is deleted after 30 days without use.\n" +
                "To delete your address: https://ledger-lab.test/delete?ref=${tokens(email).single()}\n",
        )
    }

    @Test
    fun `limit mail names the count, hours and UTC time`() {
        val email = address()
        repeat(4) { requestLink(email) }
        val freesAt = jdbc.sql("SELECT window_frees_at FROM invite_mails WHERE kind = 'LIMIT'").query(java.sql.Timestamp::class.java).single().toInstant()
        val limitToken = jdbc.sql("SELECT token FROM invite_mails WHERE kind = 'LIMIT'").query(String::class.java).single()

        sender.sendDue()

        val message = objectMapper.readTree(mailedSends().last().body).get("Messages").get(0)
        assertThat(message.get("Subject").asString()).isEqualTo("Ledger Lab: link limit reached")
        assertThat(message.get("TextPart").asString()).isEqualTo(
            "You have asked for a link 3 times in 24 hours, the limit.\n" +
                "Ask again after ${UTC_MINUTES.format(minuteAfter(freesAt))} UTC.\n\n" +
                "The link works for 7 days. Your address is deleted after 30 days without use.\n" +
                "To delete your address: https://ledger-lab.test/delete?ref=$limitToken\n",
        )
    }

    @Test
    fun `the mail switches the provider's click and open tracking off`() {
        requestLink(address())

        sender.sendDue()

        val message = objectMapper.readTree(mailedSends().single().body).get("Messages").get(0)
        assertThat(message.get("TrackClicks").asString()).isEqualTo("disabled")
        assertThat(message.get("TrackOpens").asString()).isEqualTo("disabled")
        assertThat(mailedSends().single().headers[HttpHeaders.AUTHORIZATION.lowercase()])
            .isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("test-key:test-secret".toByteArray()))
    }

    @Test
    fun `a failed send is retried with a growing pause, then marked failed`() {
        requestLink(address())
        mailjet.status = 500
        val pauses = mutableListOf<Long>()

        repeat(props.mail.maxAttempts) {
            makeDue()
            sender.sendDue()
            pauses += count("SELECT CAST(extract(epoch FROM next_attempt_at - clock_timestamp()) AS bigint) FROM invite_mails")
        }

        assertThat(mailedSends()).hasSize(props.mail.maxAttempts)
        assertThat(pauses.dropLast(1)).isSorted().allSatisfy { assertThat(it).isPositive() }
        assertThat(count("SELECT attempts FROM invite_mails")).isEqualTo(props.mail.maxAttempts.toLong())
        assertThat(count("SELECT count(*) FROM invite_mails WHERE failed_at IS NOT NULL AND sent_at IS NULL")).isEqualTo(1)
        makeDue()
        assertThat(sender.sendDue()).isZero()
    }

    @Test
    fun `a mail the provider refuses is marked failed at once`() {
        requestLink(address())
        mailjet.status = 400

        sender.sendDue()

        assertThat(count("SELECT count(*) FROM invite_mails WHERE failed_at IS NOT NULL AND attempts = 1")).isEqualTo(1)
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `sending logs neither the address nor the token`(output: CapturedOutput) {
        val email = address()
        repeat(4) { requestLink(email) }
        mailjet.status = 500
        sender.sendDue()
        mailjet.status = 200
        makeDue()
        sender.sendDue()

        assertThat(mailedSends()).hasSize(8)
        assertThat(output.out).contains("LINK sent").contains("LIMIT sent").doesNotContain(email).doesNotContain(tokens(email).first())
    }

    @Test
    fun `the mailed link opens a session and counts a visit`() {
        val email = address()
        requestLink(email)
        val token = tokens(email).single()

        assertThat(enter(token).statusCode).isEqualTo(HttpStatus.CREATED)

        assertThat(jdbc.sql("SELECT count FROM invite_visits WHERE token = :token").param("token", token).query(Long::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `an address unused for the retention is deleted, its invite, sessions and runs stay`() {
        val email = address()
        requestLink(email)
        sender.sendDue()
        val token = tokens(email).single()
        start(sessionOf(token))
        ageEverything(Duration.ofDays(30).plusHours(1))

        assertThat(cleanup.deleteUnused()).isEqualTo(1)

        assertThat(count("SELECT count(*) FROM invite_emails")).isZero()
        assertThat(count("SELECT count(*) FROM invite_requests")).isZero()
        assertThat(count("SELECT count(*) FROM invite_mails WHERE email IS NULL")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM sessions")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM runs")).isEqualTo(1)
    }

    @Test
    fun `a recent visit or request keeps the address`() {
        val visited = address()
        val asked = address()
        requestLink(visited)
        requestLink(asked)
        ageEverything(Duration.ofDays(31))
        enter(tokens(visited).single())
        requestLink(asked)

        assertThat(cleanup.deleteUnused()).isZero()
        assertThat(count("SELECT count(*) FROM invite_emails")).isEqualTo(2)
    }

    private fun ageEverything(by: Duration) {
        val millis = by.toMillis()
        listOf(
            "UPDATE invite_emails SET created_at = created_at - CAST(:millis AS bigint) * interval '1 millisecond'",
            "UPDATE invite_requests SET requested_at = requested_at - CAST(:millis AS bigint) * interval '1 millisecond'",
            "UPDATE invite_mails SET requested_at = requested_at - CAST(:millis AS bigint) * interval '1 millisecond'",
            "UPDATE invite_visits SET last_seen = last_seen - CAST(:millis AS bigint) * interval '1 millisecond'",
        ).forEach { jdbc.sql(it).param("millis", millis).update() }
    }

    private fun makeDue() {
        jdbc.sql("UPDATE invite_mails SET next_attempt_at = clock_timestamp()").update()
    }

    private fun jsonHeaders() = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    private companion object {
        const val CONCURRENT = 12
        val UTC_MINUTES: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}

internal fun IntegrationTest.requestLink(email: String?, turnstileToken: String? = null) =
    http.postForEntity("/api/invite-requests", mapOf("email" to email, "turnstileToken" to turnstileToken), String::class.java)
