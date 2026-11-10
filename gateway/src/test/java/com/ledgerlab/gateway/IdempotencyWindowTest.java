package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.gateway.service.ExpiredKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** A key is remembered for the idempotency window, which is as long as transfers are held (ADR 0022). */
class IdempotencyWindowTest extends IntegrationTest {

    private static final Duration MARGIN = Duration.ofMinutes(1);

    @Autowired ExpiredKeys expiredKeys;

    private Instant justInsideTheWindow() {
        return Instant.now().minus(props.periods().retention()).plus(MARGIN);
    }

    private Instant justPastTheWindow() {
        return Instant.now().minus(props.periods().retention()).minus(MARGIN);
    }

    private long keys(UUID transferId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM transfer_keys WHERE transfer_id = ?", Long.class, transferId);
    }

    @Test
    void a_key_still_inside_the_window_gets_the_first_answer() {
        var acceptedAt = justInsideTheWindow();
        var transferId = acceptedAt(acceptedAt);

        var repeated = submit(body(transferId));

        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(Instant.parse((String) repeated.getBody().get("acceptedAt"))).isEqualTo(storedAcceptedAt(transferId));
        assertThat(Duration.between(acceptedAt, storedAcceptedAt(transferId)).abs()).isLessThan(Duration.ofMillis(1));
        assertThat(transferRows(transferId)).isEqualTo(1);
        assertThat(status(transferId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("the same key after the window is taken as a new transfer, whether or not the old key was deleted yet")
    void the_same_key_after_the_window_is_taken_as_a_new_transfer_whether_or_not_the_old_key_was_deleted_yet() {
        var before = Instant.now();
        var transferId = acceptedAt(justPastTheWindow());
        assertThat(status(transferId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var again = submit(body(transferId, 5, 6, 777));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(Instant.parse((String) again.getBody().get("acceptedAt"))).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(keys(transferId)).isEqualTo(1);
        assertThat(transferRows(transferId)).isEqualTo(2);
        assertThat(status(transferId).getBody().get("acceptedAt")).isEqualTo(again.getBody().get("acceptedAt"));
        assertThat(submit(body(transferId, 5, 6, 777)).getBody().get("acceptedAt")).isEqualTo(again.getBody().get("acceptedAt"));
    }

    @Test
    void keys_older_than_the_window_are_deleted_and_younger_ones_stay() {
        var old = acceptedAt(justPastTheWindow());
        var alsoOld = acceptedAt(justPastTheWindow().minus(MARGIN));
        var young = acceptedAt(justInsideTheWindow());

        var deleted = expiredKeys.delete(Instant.now());

        assertThat(deleted).isEqualTo(2);
        assertThat(keys(old) + keys(alsoOld)).isZero();
        assertThat(keys(young)).isEqualTo(1);
    }

    @Test
    void more_expired_keys_than_one_statement_takes_are_all_deleted() {
        var count = props.periods().keyCleanupBatch() * 2 + 1;
        for (var i = 0; i < count; i++) {
            acceptedAt(justPastTheWindow());
        }

        assertThat(expiredKeys.delete(Instant.now())).isEqualTo(count);
    }
}
