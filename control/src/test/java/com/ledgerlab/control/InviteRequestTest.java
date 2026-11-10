package com.ledgerlab.control;

import static com.ledgerlab.control.entry.MailTexts.minuteAfter;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.api.ControlErrors;
import com.ledgerlab.control.entry.AddressCleanup;
import com.ledgerlab.control.entry.MailSender;
import com.ledgerlab.control.entry.MailjetClient;
import com.ledgerlab.control.entry.TurnstileClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
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

class InviteRequestTest extends IntegrationTest {

    private static final int CONCURRENT = 12;
    private static final DateTimeFormatter BERLIN_MINUTES = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z", Locale.ENGLISH).withZone(ZoneId.of("Europe/Berlin"));

    @Autowired MailSender sender;
    @Autowired AddressCleanup cleanup;

    private String address() {
        return "visitor-" + UUID.randomUUID() + "@example.org";
    }

    private List<String> kinds(String email) {
        return jdbc.sql("SELECT kind FROM invite_mails WHERE email = :email ORDER BY mail_id").param("email", email).query((rs, row) -> rs.getString(1)).list();
    }

    private List<String> tokens(String email) {
        return jdbc.sql("SELECT token FROM invite_mails WHERE email = :email AND kind = 'LINK' ORDER BY mail_id").param("email", email).query((rs, row) -> rs.getString(1)).list();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void ageMails(String email, Duration by) {
        jdbc.sql("UPDATE invite_mails SET requested_at = requested_at - CAST(:millis AS bigint) * interval '1 millisecond' WHERE email = :email")
            .param("millis", by.toMillis()).param("email", email).update();
    }

    private List<StubService.Call> mailedSends() {
        return mailjet.callsTo("POST", MailjetClient.SEND_PATH);
    }

    private void requestLinks(int times, String email) {
        for (var i = 0; i < times; i++) {
            requestLink(email);
        }
    }

    @Test
    @DisplayName("a new, a known and an exhausted address get the same answer")
    void a_new_a_known_and_an_exhausted_address_get_the_same_answer() {
        var known = address();
        requestLinks(5, known);

        List.of(address(), known).forEach(email -> {
            var response = requestLink(email);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isEqualTo("{}");
        });
    }

    @Test
    @DisplayName("three requests mail the link, the fourth the limit and the fifth nothing")
    void three_requests_mail_the_link_the_fourth_the_limit_and_the_fifth_nothing() {
        var email = address();

        requestLinks(6, email);

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT");
        assertThat(count("SELECT count(*) FROM invite_requests")).isEqualTo(6);
    }

    @Test
    void mails_older_than_the_window_do_not_count() {
        var email = address();
        requestLinks(4, email);
        ageMails(email, Duration.ofHours(24).plusMinutes(1));

        requestLink(email);

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT", "LINK");
    }

    @Test
    void the_limit_mail_names_when_the_oldest_link_leaves_the_window() {
        var email = address();
        requestLink(email);
        ageMails(email, Duration.ofHours(5));
        requestLinks(3, email);
        var oldestLink = jdbc.sql("SELECT min(requested_at) FROM invite_mails WHERE email = :email").param("email", email)
            .query(Timestamp.class).single().toInstant();

        var freesAt = jdbc.sql("SELECT window_frees_at FROM invite_mails WHERE kind = 'LIMIT'").query(Timestamp.class).single().toInstant();
        sender.sendDue();

        assertThat(freesAt).isEqualTo(oldestLink.plus(Duration.ofHours(24)));
        var limitMail = mailedSends().getLast().body();
        assertThat(limitMail).contains("Ask again after " + BERLIN_MINUTES.format(minuteAfter(freesAt)) + ".");
    }

    @Test
    @DisplayName("an active invite is mailed again, not replaced")
    void an_active_invite_is_mailed_again_not_replaced() {
        var email = address();

        requestLinks(3, email);

        assertThat(tokens(email).stream().distinct().toList()).hasSize(1);
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(1);
    }

    @Test
    void asking_again_while_the_link_works_extends_it_by_the_lifetime() {
        var email = address();
        requestLink(email);
        var token = single(tokens(email));
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() + interval '2 hours' WHERE token = :token").param("token", token).update();

        requestLink(email);

        var left = jdbc.sql("SELECT CAST(extract(epoch FROM expires_at - clock_timestamp()) AS bigint) FROM invite_tokens WHERE token = :token")
            .param("token", token).query(Long.class).single();
        assertThat(tokens(email)).containsOnly(token);
        assertThat(left).isBetween(props.invites().email().lifetime().toSeconds() - 60, props.invites().email().lifetime().toSeconds());
    }

    @Test
    void a_mail_from_before_midnight_but_within_the_window_still_counts() {
        var email = address();
        requestLinks(3, email);
        jdbc.sql("UPDATE invite_mails SET requested_at = date_trunc('day', clock_timestamp()) - interval '1 second' WHERE email = :email")
            .param("email", email).update();

        requestLink(email);

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT");
    }

    @Test
    void an_expired_invite_gets_a_new_one() {
        var email = address();
        requestLink(email);
        var first = single(tokens(email));
        jdbc.sql("UPDATE invite_tokens SET expires_at = now() - interval '1 minute' WHERE token = :token").param("token", first).update();

        requestLink(email);

        assertThat(tokens(email)).hasSize(2).doesNotHaveDuplicates();
        assertThat(jdbc.sql("SELECT token FROM invite_emails WHERE email = :email").param("email", email).query(String.class).single())
            .isEqualTo(tokens(email).getLast());
    }

    @Test
    void a_mailed_invite_has_the_configured_lifetime_and_run_quota() {
        var email = address();
        requestLink(email);

        var invite = jdbc.sql("SELECT quota, extract(epoch FROM expires_at - created_at) AS seconds FROM invite_tokens")
            .query((rs, row) -> Map.entry(rs.getInt("quota"), rs.getLong("seconds"))).single();
        int quota = invite.getKey();
        long lifetime = invite.getValue();

        assertThat(quota).isEqualTo(props.invites().email().runsPerHour());
        assertThat(lifetime).isEqualTo(props.invites().email().lifetime().toSeconds());
        assertThat(single(tokens(email))).startsWith("mail-");
    }

    @Test
    void asking_again_does_not_reset_the_run_quota() {
        var email = address();
        requestLink(email);
        start(sessionOf(single(tokens(email))));

        requestLink(email);
        var sessionId = sessionOf(tokens(email).getLast());

        var view = get("/api/sessions/" + sessionId).getBody();
        assertThat(view.get("runsLastHour")).isEqualTo(1);
    }

    @Test
    void concurrent_requests_for_one_address_stay_within_the_limits() throws Exception {
        var email = address();
        var ready = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<Future<ResponseEntity<String>>>();
            for (var i = 0; i < CONCURRENT; i++) {
                requests.add(pool.submit(() -> {
                    ready.await();
                    return requestLink(email);
                }));
            }
            ready.countDown();
            for (var request : requests) {
                request.get();
            }
        }

        assertThat(kinds(email)).containsExactly("LINK", "LINK", "LINK", "LIMIT");
        assertThat(count("SELECT count(*) FROM invite_emails")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM invite_requests")).isEqualTo((long) CONCURRENT);
    }

    @Test
    @DisplayName("over the daily limit of all mails is 429 with Retry-After, and nothing is recorded")
    void over_the_daily_limit_of_all_mails_is_429_with_Retry_After_and_nothing_is_recorded() {
        var exhausted = address();
        requestLinks(5, exhausted);
        for (var i = 0; i < props.invites().email().dailyMails() - 4; i++) {
            requestLink(address());
        }
        var requestsBefore = count("SELECT count(*) FROM invite_requests");

        var refused = Stream.of(address(), exhausted).map(this::requestLink).toList();

        refused.forEach(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(objectMapper.readTree(response.getBody()).get("error").asString()).isEqualTo(ControlErrors.DAILY_LIMIT);
            assertThat(Long.parseLong(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
        });
        assertThat(count("SELECT count(*) FROM invite_requests")).isEqualTo(requestsBefore);
        assertThat(count("SELECT count(*) FROM invite_mails")).isEqualTo((long) props.invites().email().dailyMails());
    }

    @Test
    void an_address_is_trimmed_and_lowercased() {
        var email = address();

        requestLink("  " + email.toUpperCase(Locale.ROOT) + " ");
        requestLink(email);

        assertThat(kinds(email)).containsExactly("LINK", "LINK");
        assertThat(count("SELECT count(*) FROM invite_emails")).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty, malformed or too long address is 400 and nothing is recorded")
    void an_empty_malformed_or_too_long_address_is_400_and_nothing_is_recorded() {
        var tooLong = "a".repeat(255 - "@example.org".length()) + "@example.org";

        List.of("", "   ", "no-at-sign", "two@at@signs.org", "with space@example.org", "a,b@example.org", tooLong).forEach(email -> {
            var response = requestLink(email);
            assertThat(response.getStatusCode()).describedAs(email).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(objectMapper.readTree(response.getBody()).get("error").asString()).isEqualTo("invalid-email");
        });
        assertThat(requestLink(null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var unreadable = http.postForEntity("/api/invite-requests", new HttpEntity<>("{", jsonHeaders()), String.class);
        assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("SELECT count(*) FROM invite_requests")).isZero();
        assertThat(requestLink(tooLong.substring(1)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void without_a_Turnstile_secret_the_check_is_skipped() {
        assertThat(requestLink(address(), null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(turnstile.callsTo("POST", TurnstileClient.SITEVERIFY_PATH)).isEmpty();
    }

    @Test
    void a_mail_is_sent_once_and_marked_sent() {
        var email = address();
        requestLink(email);

        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(sender.sendDue()).isZero();

        var body = objectMapper.readTree(single(mailedSends()).body()).get("Messages").get(0);
        assertThat(body.get("To").get(0).get("Email").asString()).isEqualTo(email);
        assertThat(body.get("TextPart").asString()).contains("https://ledger-lab.test/?ref=" + single(tokens(email)));
        assertThat(body.get("TextPart").asString()).contains("The link works for 7 days. Your address is deleted after 30 days without use.");
        assertThat(count("SELECT count(*) FROM invite_mails WHERE sent_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void link_mail_reads_as_before() {
        var email = address();
        requestLink(email);

        sender.sendDue();

        var message = objectMapper.readTree(single(mailedSends()).body()).get("Messages").get(0);
        assertThat(message.get("Subject").asString()).isEqualTo("Your Ledger Lab link");
        assertThat(message.get("TextPart").asString()).isEqualTo(
            "Here is your link to Ledger Lab:\n\nhttps://ledger-lab.test/?ref=" + single(tokens(email)) + "\n\n"
                + "The link works for 7 days. Your address is deleted after 30 days without use.\n"
                + "To delete your address: https://ledger-lab.test/delete?ref=" + single(tokens(email)) + "\n");
    }

    @Test
    @DisplayName("limit mail names the count, hours and the time in Berlin")
    void limit_mail_names_the_count_hours_and_the_time_in_Berlin() {
        var email = address();
        requestLinks(4, email);
        var freesAt = jdbc.sql("SELECT window_frees_at FROM invite_mails WHERE kind = 'LIMIT'").query(Timestamp.class).single().toInstant();
        var limitToken = jdbc.sql("SELECT token FROM invite_mails WHERE kind = 'LIMIT'").query(String.class).single();

        sender.sendDue();

        var message = objectMapper.readTree(mailedSends().getLast().body()).get("Messages").get(0);
        assertThat(message.get("Subject").asString()).isEqualTo("Ledger Lab: link limit reached");
        assertThat(message.get("TextPart").asString()).isEqualTo(
            "You have asked for a link 3 times in 24 hours, the limit.\n"
                + "Ask again after " + BERLIN_MINUTES.format(minuteAfter(freesAt)) + ".\n\n"
                + "The link works for 7 days. Your address is deleted after 30 days without use.\n"
                + "To delete your address: https://ledger-lab.test/delete?ref=" + limitToken + "\n");
    }

    @Test
    @DisplayName("the mail switches the provider's click and open tracking off")
    void the_mail_switches_the_providers_click_and_open_tracking_off() {
        requestLink(address());

        sender.sendDue();

        var message = objectMapper.readTree(single(mailedSends()).body()).get("Messages").get(0);
        assertThat(message.get("TrackClicks").asString()).isEqualTo("disabled");
        assertThat(message.get("TrackOpens").asString()).isEqualTo("disabled");
        assertThat(single(mailedSends()).headers().get(HttpHeaders.AUTHORIZATION.toLowerCase(Locale.ROOT)))
            .isEqualTo("Basic " + Base64.getEncoder().encodeToString("test-key:test-secret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a failed send is retried with a growing pause, then marked failed")
    void a_failed_send_is_retried_with_a_growing_pause_then_marked_failed() {
        requestLink(address());
        mailjet.status = 500;
        var pauses = new ArrayList<Long>();

        for (var i = 0; i < props.mail().maxAttempts(); i++) {
            makeDue();
            sender.sendDue();
            pauses.add(count("SELECT CAST(extract(epoch FROM next_attempt_at - clock_timestamp()) AS bigint) FROM invite_mails"));
        }

        assertThat(mailedSends()).hasSize(props.mail().maxAttempts());
        assertThat(pauses.subList(0, pauses.size() - 1)).isSorted().allSatisfy(it -> assertThat(it).isPositive());
        assertThat(count("SELECT attempts FROM invite_mails")).isEqualTo((long) props.mail().maxAttempts());
        assertThat(count("SELECT count(*) FROM invite_mails WHERE failed_at IS NOT NULL AND sent_at IS NULL")).isEqualTo(1);
        makeDue();
        assertThat(sender.sendDue()).isZero();
    }

    @Test
    void a_mail_the_provider_refuses_is_marked_failed_at_once() {
        requestLink(address());
        mailjet.status = 400;

        sender.sendDue();

        assertThat(count("SELECT count(*) FROM invite_mails WHERE failed_at IS NOT NULL AND attempts = 1")).isEqualTo(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("sending logs neither the address nor the token")
    void sending_logs_neither_the_address_nor_the_token(CapturedOutput output) {
        var email = address();
        requestLinks(4, email);
        mailjet.status = 500;
        sender.sendDue();
        mailjet.status = 200;
        makeDue();
        sender.sendDue();

        assertThat(mailedSends()).hasSize(8);
        assertThat(output.getOut()).contains("LINK sent").contains("LIMIT sent").doesNotContain(email).doesNotContain(tokens(email).getFirst());
    }

    @Test
    void the_mailed_link_opens_a_session_and_counts_a_visit() {
        var email = address();
        requestLink(email);
        var token = single(tokens(email));

        assertThat(enter(token).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(jdbc.sql("SELECT count FROM invite_visits WHERE token = :token").param("token", token).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("an address unused for the retention is deleted, its invite, sessions and runs stay")
    void an_address_unused_for_the_retention_is_deleted_its_invite_sessions_and_runs_stay() {
        var email = address();
        requestLink(email);
        sender.sendDue();
        var token = single(tokens(email));
        start(sessionOf(token));
        ageEverything(Duration.ofDays(30).plusHours(1));

        assertThat(cleanup.deleteUnused()).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM invite_emails")).isZero();
        assertThat(count("SELECT count(*) FROM invite_requests")).isZero();
        assertThat(count("SELECT count(*) FROM invite_mails WHERE email IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM invite_tokens")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sessions")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM runs")).isEqualTo(1);
    }

    @Test
    void a_recent_visit_or_request_keeps_the_address() {
        var visited = address();
        var asked = address();
        requestLink(visited);
        requestLink(asked);
        ageEverything(Duration.ofDays(31));
        enter(single(tokens(visited)));
        requestLink(asked);

        assertThat(cleanup.deleteUnused()).isZero();
        assertThat(count("SELECT count(*) FROM invite_emails")).isEqualTo(2);
    }

    private void ageEverything(Duration by) {
        var millis = by.toMillis();
        List.of(
            "UPDATE invite_emails SET created_at = created_at - CAST(:millis AS bigint) * interval '1 millisecond'",
            "UPDATE invite_requests SET requested_at = requested_at - CAST(:millis AS bigint) * interval '1 millisecond'",
            "UPDATE invite_mails SET requested_at = requested_at - CAST(:millis AS bigint) * interval '1 millisecond'",
            "UPDATE invite_visits SET last_seen = last_seen - CAST(:millis AS bigint) * interval '1 millisecond'")
            .forEach(it -> jdbc.sql(it).param("millis", millis).update());
    }

    private void makeDue() {
        jdbc.sql("UPDATE invite_mails SET next_attempt_at = clock_timestamp()").update();
    }

    private HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
