package com.ledgerlab.control

import com.ledgerlab.control.api.ControlErrors
import com.ledgerlab.control.entry.AddressCleanup
import com.ledgerlab.control.entry.InviteMailRepository
import com.ledgerlab.control.entry.MailSender
import com.ledgerlab.control.entry.MailjetClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class AddressDeletionTest : IntegrationTest() {

    @Autowired lateinit var cleanup: AddressCleanup
    @Autowired lateinit var sender: MailSender
    @Autowired lateinit var mails: InviteMailRepository
    @Autowired lateinit var tx: TransactionTemplate

    private fun address() = "visitor-${UUID.randomUUID()}@example.org"

    private fun tokenOf(email: String): String =
        jdbc.sql("SELECT token FROM invite_emails WHERE email = :email").param("email", email).query(String::class.java).single()

    private fun deleteAt(email: String): Instant =
        jdbc.sql("SELECT delete_at FROM invite_emails WHERE email = :email").param("email", email).query(Timestamp::class.java).single().toInstant()

    private fun deletionAsked(email: String): Boolean =
        jdbc.sql("SELECT delete_at IS NOT NULL FROM invite_emails WHERE email = :email").param("email", email).query(Boolean::class.java).single()

    private fun stored(email: String): Boolean =
        jdbc.sql("SELECT count(*) FROM invite_emails WHERE email = :email").param("email", email).query(Long::class.java).single() > 0

    private fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.java).single()

    private fun askDeletion(token: String?): ResponseEntity<String> =
        http.postForEntity("/api/address-deletions", mapOf("token" to token), String::class.java)

    private fun deleteAtOf(response: ResponseEntity<String>): Instant = Instant.parse(objectMapper.readTree(response.body).get("deleteAt").asString())

    private fun alreadyAskedOf(response: ResponseEntity<String>): Boolean = objectMapper.readTree(response.body).get("alreadyAsked").asBoolean()

    private fun errorOf(response: ResponseEntity<String>): String = objectMapper.readTree(response.body).get("error").asString()

    private fun makeDeletionDue(email: String) {
        jdbc.sql("UPDATE invite_emails SET delete_at = clock_timestamp() - interval '1 second' WHERE email = :email").param("email", email).update()
    }

    private fun mailedTexts(): List<String> =
        mailjet.callsTo("POST", MailjetClient.SEND_PATH).map { objectMapper.readTree(it.body).get("Messages").get(0).get("TextPart").asString() }

    @Test
    fun `a deletion asked by the mailed link is due a window from now`() {
        val email = address()
        requestLink(email)
        val asked = Instant.now()

        val response = askDeletion(tokenOf(email))

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val window = props.invites.email.window
        assertThat(deleteAtOf(response)).isEqualTo(deleteAt(email)).isBetween(asked + window, Instant.now() + window)
    }

    @Test
    fun `asking again keeps the first deletion time and says it was already asked`() {
        val email = address()
        requestLink(email)
        val firstResponse = askDeletion(tokenOf(email))
        val first = deleteAtOf(firstResponse)

        val again = askDeletion(tokenOf(email))

        assertThat(alreadyAskedOf(firstResponse)).isFalse()
        assertThat(again.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(alreadyAskedOf(again)).isTrue()
        assertThat(deleteAtOf(again)).isEqualTo(first)
        assertThat(deleteAt(email)).isEqualTo(first)
    }

    @Test
    fun `a link from an older mail finds the address after its invite was replaced`() {
        val email = address()
        requestLink(email)
        val older = tokenOf(email)
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute' WHERE token = :token").param("token", older).update()
        requestLink(email)
        assertThat(tokenOf(email)).isNotEqualTo(older)

        assertThat(askDeletion(older).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(deletionAsked(email)).isTrue()
    }

    @Test
    fun `an unknown token, a hand-made invite and a deleted address get 404 unknown-link`() {
        val deleted = address()
        requestLink(deleted)
        val deletedToken = tokenOf(deleted)
        askDeletion(deletedToken)
        makeDeletionDue(deleted)
        cleanup.deleteRequested()

        listOf("mail-${UUID.randomUUID()}", invite(), deletedToken).forEach { token ->
            val response = askDeletion(token)
            assertThat(response.statusCode).describedAs(token).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(errorOf(response)).isEqualTo(ControlErrors.UNKNOWN_LINK)
        }
    }

    @Test
    fun `a missing or empty token is 400 and nothing changes`() {
        val email = address()
        requestLink(email)

        listOf(null, "", "  ").forEach { token ->
            val response = askDeletion(token)
            assertThat(response.statusCode).describedAs("[$token]").isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(errorOf(response)).isEqualTo(ControlErrors.TOKEN_REQUIRED)
        }
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        assertThat(http.postForEntity("/api/address-deletions", HttpEntity("{", headers), String::class.java).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(count("SELECT count(*) FROM invite_emails WHERE delete_at IS NOT NULL")).isZero()
    }

    @Test
    fun `a link request after a deletion request neither cancels nor moves it`() {
        val email = address()
        requestLink(email)
        val due = deleteAtOf(askDeletion(tokenOf(email)))

        requestLink(email)
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute'").update()
        requestLink(email)

        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(2)
        assertThat(deleteAt(email)).isEqualTo(due)
    }

    @Test
    fun `an address due later is kept by the deletion tick`() {
        val email = address()
        requestLink(email)
        askDeletion(tokenOf(email))

        assertThat(cleanup.deleteRequested()).isZero()
        assertThat(stored(email)).isTrue()
    }

    @Test
    fun `an address past its deletion time is deleted by the tick, its invite, sessions and runs stay`() {
        val email = address()
        val kept = address()
        requestLink(email)
        requestLink(kept)
        sender.sendDue()
        start(sessionOf(tokenOf(email)))
        askDeletion(tokenOf(email))
        makeDeletionDue(email)

        assertThat(cleanup.deleteRequested()).isEqualTo(1)

        assertThat(stored(email)).isFalse()
        assertThat(stored(kept)).isTrue()
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(2)
        assertThat(count("SELECT count(*) FROM sessions")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM runs")).isEqualTo(1)
    }

    @Test
    fun `a deleted address takes its requests and leaves its mails without an address`() {
        val email = address()
        repeat(2) { requestLink(email) }
        askDeletion(tokenOf(email))
        makeDeletionDue(email)

        cleanup.deleteRequested()

        assertThat(count("SELECT count(*) FROM invite_requests")).isZero()
        assertThat(count("SELECT count(*) FROM invite_mails WHERE email IS NULL")).isEqualTo(2)
    }

    @Test
    fun `the deletion tick waits for the requests' lock`() {
        val email = address()
        requestLink(email)
        askDeletion(tokenOf(email))
        makeDeletionDue(email)

        val deleted = whileLockHeld({ cleanup.deleteRequested() }) { assertThat(stored(email)).isTrue() }

        assertThat(deleted).isEqualTo(1)
        assertThat(stored(email)).isFalse()
    }

    @Test
    fun `a deletion request waits for the requests' lock`() {
        val email = address()
        requestLink(email)
        val token = tokenOf(email)

        val response = whileLockHeld({ askDeletion(token) }) { assertThat(deletionAsked(email)).isFalse() }

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(deletionAsked(email)).isTrue()
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `deleting logs neither the address nor the token`(output: CapturedOutput) {
        val email = address()
        requestLink(email)
        val token = tokenOf(email)
        askDeletion(token)
        makeDeletionDue(email)

        cleanup.deleteRequested()

        assertThat(output.out).contains("Deleted 1 addresses on request").doesNotContain(email).doesNotContain(token)
    }

    @Test
    fun `the link and limit mails carry a delete link with their own token`() {
        val email = address()
        requestLink(email)
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute'").update()
        repeat(3) { requestLink(email) }
        val mailTokens = jdbc.sql("SELECT token FROM invite_mails ORDER BY mail_id").query(String::class.java).list()

        sender.sendDue()

        assertThat(mailTokens.distinct()).hasSize(2)
        assertThat(jdbc.sql("SELECT kind FROM invite_mails ORDER BY mail_id").query(String::class.java).list()).containsExactly("LINK", "LINK", "LINK", "LIMIT")
        assertThat(mailedTexts()).hasSize(4).zipSatisfy(mailTokens) { text, token ->
            assertThat(text).contains("To delete your address: https://ledger-lab.test/delete?ref=$token")
        }
    }

    @Test
    fun `GET delete serves the page and deletes nothing`() {
        val email = address()
        requestLink(email)

        val page = http.getForEntity("/delete?ref=${tokenOf(email)}", String::class.java)

        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(page.body).isEqualTo(http.getForObject("/", String::class.java))
        assertThat(deletionAsked(email)).isFalse()
    }

    private fun <T> whileLockHeld(action: () -> T, meanwhile: () -> Unit): T {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            pool.submit {
                tx.executeWithoutResult {
                    mails.lock()
                    held.countDown()
                    release.await()
                }
            }
            held.await()
            val result = pool.submit(action)
            try {
                await().atMost(Duration.ofSeconds(5)).until { count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") > 0 }
                meanwhile()
            } finally {
                release.countDown()
            }
            return result.get()
        }
    }
}
