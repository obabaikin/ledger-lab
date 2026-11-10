package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.mapOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.control.api.ControlErrors;
import com.ledgerlab.control.entry.AddressCleanup;
import com.ledgerlab.control.entry.InviteMailRepository;
import com.ledgerlab.control.entry.MailSender;
import com.ledgerlab.control.entry.MailjetClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

class AddressDeletionTest extends IntegrationTest {

    @Autowired AddressCleanup cleanup;
    @Autowired MailSender sender;
    @Autowired InviteMailRepository mails;
    @Autowired TransactionTemplate tx;

    private String address() {
        return "visitor-" + UUID.randomUUID() + "@example.org";
    }

    private String tokenOf(String email) {
        return jdbc.sql("SELECT token FROM invite_emails WHERE email = :email").param("email", email).query(String.class).single();
    }

    private Instant deleteAt(String email) {
        return jdbc.sql("SELECT delete_at FROM invite_emails WHERE email = :email").param("email", email).query(Timestamp.class).single().toInstant();
    }

    private boolean deletionAsked(String email) {
        return jdbc.sql("SELECT delete_at IS NOT NULL FROM invite_emails WHERE email = :email").param("email", email).query(Boolean.class).single();
    }

    private boolean stored(String email) {
        return jdbc.sql("SELECT count(*) FROM invite_emails WHERE email = :email").param("email", email).query(Long.class).single() > 0;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private ResponseEntity<String> askDeletion(String token) {
        return http.postForEntity("/api/address-deletions", mapOf("token", token), String.class);
    }

    private Instant deleteAtOf(ResponseEntity<String> response) {
        return Instant.parse(objectMapper.readTree(response.getBody()).get("deleteAt").asString());
    }

    private boolean alreadyAskedOf(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody()).get("alreadyAsked").asBoolean();
    }

    private String errorOf(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody()).get("error").asString();
    }

    private void makeDeletionDue(String email) {
        jdbc.sql("UPDATE invite_emails SET delete_at = clock_timestamp() - interval '1 second' WHERE email = :email").param("email", email).update();
    }

    private List<String> mailedTexts() {
        return mailjet.callsTo("POST", MailjetClient.SEND_PATH).stream()
            .map(it -> objectMapper.readTree(it.body()).get("Messages").get(0).get("TextPart").asString())
            .toList();
    }

    @Test
    void a_deletion_asked_by_the_mailed_link_is_due_a_window_from_now() {
        var email = address();
        requestLink(email);
        var asked = Instant.now();

        var response = askDeletion(tokenOf(email));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var window = props.invites().email().window();
        assertThat(deleteAtOf(response)).isEqualTo(deleteAt(email)).isBetween(asked.plus(window), Instant.now().plus(window));
    }

    @Test
    void asking_again_keeps_the_first_deletion_time_and_says_it_was_already_asked() {
        var email = address();
        requestLink(email);
        var firstResponse = askDeletion(tokenOf(email));
        var first = deleteAtOf(firstResponse);

        var again = askDeletion(tokenOf(email));

        assertThat(alreadyAskedOf(firstResponse)).isFalse();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(alreadyAskedOf(again)).isTrue();
        assertThat(deleteAtOf(again)).isEqualTo(first);
        assertThat(deleteAt(email)).isEqualTo(first);
    }

    @Test
    void a_link_from_an_older_mail_finds_the_address_after_its_invite_was_replaced() {
        var email = address();
        requestLink(email);
        var older = tokenOf(email);
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute' WHERE token = :token").param("token", older).update();
        requestLink(email);
        assertThat(tokenOf(email)).isNotEqualTo(older);

        assertThat(askDeletion(older).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(deletionAsked(email)).isTrue();
    }

    @Test
    @DisplayName("an unknown token, a hand-made invite and a deleted address get 404 unknown-link")
    void an_unknown_token_a_hand_made_invite_and_a_deleted_address_get_404_unknown_link() {
        var deleted = address();
        requestLink(deleted);
        var deletedToken = tokenOf(deleted);
        askDeletion(deletedToken);
        makeDeletionDue(deleted);
        cleanup.deleteRequested();

        List.of("mail-" + UUID.randomUUID(), invite(), deletedToken).forEach(token -> {
            var response = askDeletion(token);
            assertThat(response.getStatusCode()).describedAs(token).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorOf(response)).isEqualTo(ControlErrors.UNKNOWN_LINK);
        });
    }

    @Test
    void a_missing_or_empty_token_is_400_and_nothing_changes() {
        var email = address();
        requestLink(email);

        Arrays.asList(null, "", "  ").forEach(token -> {
            var response = askDeletion(token);
            assertThat(response.getStatusCode()).describedAs("[" + token + "]").isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorOf(response)).isEqualTo(ControlErrors.TOKEN_REQUIRED);
        });
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/api/address-deletions", new HttpEntity<>("{", headers), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("SELECT count(*) FROM invite_emails WHERE delete_at IS NOT NULL")).isZero();
    }

    @Test
    void a_link_request_after_a_deletion_request_neither_cancels_nor_moves_it() {
        var email = address();
        requestLink(email);
        var due = deleteAtOf(askDeletion(tokenOf(email)));

        requestLink(email);
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute'").update();
        requestLink(email);

        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(2);
        assertThat(deleteAt(email)).isEqualTo(due);
    }

    @Test
    void an_address_due_later_is_kept_by_the_deletion_tick() {
        var email = address();
        requestLink(email);
        askDeletion(tokenOf(email));

        assertThat(cleanup.deleteRequested()).isZero();
        assertThat(stored(email)).isTrue();
    }

    @Test
    @DisplayName("an address past its deletion time is deleted by the tick, its invite, sessions and runs stay")
    void an_address_past_its_deletion_time_is_deleted_by_the_tick_its_invite_sessions_and_runs_stay() {
        var email = address();
        var kept = address();
        requestLink(email);
        requestLink(kept);
        sender.sendDue();
        start(sessionOf(tokenOf(email)));
        askDeletion(tokenOf(email));
        makeDeletionDue(email);

        assertThat(cleanup.deleteRequested()).isEqualTo(1);

        assertThat(stored(email)).isFalse();
        assertThat(stored(kept)).isTrue();
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM sessions")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM runs")).isEqualTo(1);
    }

    @Test
    void a_deleted_address_takes_its_requests_and_leaves_its_mails_without_an_address() {
        var email = address();
        for (var i = 0; i < 2; i++) {
            requestLink(email);
        }
        askDeletion(tokenOf(email));
        makeDeletionDue(email);

        cleanup.deleteRequested();

        assertThat(count("SELECT count(*) FROM invite_requests")).isZero();
        assertThat(count("SELECT count(*) FROM invite_mails WHERE email IS NULL")).isEqualTo(2);
    }

    @Test
    @DisplayName("the deletion tick waits for the requests' lock")
    void the_deletion_tick_waits_for_the_requests_lock() throws Exception {
        var email = address();
        requestLink(email);
        askDeletion(tokenOf(email));
        makeDeletionDue(email);

        var deleted = whileLockHeld(() -> cleanup.deleteRequested(), () -> assertThat(stored(email)).isTrue());

        assertThat(deleted).isEqualTo(1);
        assertThat(stored(email)).isFalse();
    }

    @Test
    @DisplayName("a deletion request waits for the requests' lock")
    void a_deletion_request_waits_for_the_requests_lock() throws Exception {
        var email = address();
        requestLink(email);
        var token = tokenOf(email);

        var response = whileLockHeld(() -> askDeletion(token), () -> assertThat(deletionAsked(email)).isFalse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(deletionAsked(email)).isTrue();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("deleting logs neither the address nor the token")
    void deleting_logs_neither_the_address_nor_the_token(CapturedOutput output) {
        var email = address();
        requestLink(email);
        var token = tokenOf(email);
        askDeletion(token);
        makeDeletionDue(email);

        cleanup.deleteRequested();

        assertThat(output.getOut()).contains("Deleted 1 addresses on request").doesNotContain(email).doesNotContain(token);
    }

    @Test
    void the_link_and_limit_mails_carry_a_delete_link_with_their_own_token() {
        var email = address();
        requestLink(email);
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute'").update();
        for (var i = 0; i < 3; i++) {
            requestLink(email);
        }
        var mailTokens = jdbc.sql("SELECT token FROM invite_mails ORDER BY mail_id").query(String.class).list();

        sender.sendDue();

        assertThat(mailTokens.stream().distinct().toList()).hasSize(2);
        assertThat(jdbc.sql("SELECT kind FROM invite_mails ORDER BY mail_id").query(String.class).list()).containsExactly("LINK", "LINK", "LINK", "LIMIT");
        assertThat(mailedTexts()).hasSize(4).zipSatisfy(mailTokens, (text, token) ->
            assertThat(text).contains("To delete your address: https://ledger-lab.test/delete?ref=" + token));
    }

    @Test
    void GET_delete_serves_the_page_and_deletes_nothing() {
        var email = address();
        requestLink(email);

        var page = http.getForEntity("/delete?ref=" + tokenOf(email), String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).isEqualTo(http.getForObject("/", String.class));
        assertThat(deletionAsked(email)).isFalse();
    }

    private <T> T whileLockHeld(Callable<T> action, Runnable meanwhile) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var holding = pool.submit(() -> tx.executeWithoutResult(status -> {
                mails.lock();
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            held.await();
            var result = pool.submit(action);
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") > 0);
                meanwhile.run();
            } finally {
                release.countDown();
            }
            holding.get();
            return result.get();
        }
    }
}
