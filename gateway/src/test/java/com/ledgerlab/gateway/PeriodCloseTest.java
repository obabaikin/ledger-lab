package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.gateway.api.GatewayErrors;
import com.ledgerlab.gateway.domain.Period;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.persistence.PeriodRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class PeriodCloseTest extends IntegrationTest {

    @Autowired DataSource dataSource;

    private Period currentPeriod() {
        return Period.containing(Instant.now(), props.periods().length());
    }

    @Test
    void the_summary_counts_the_transfers_accepted_in_the_period_by_status_and_reason() {
        var period = lastEndedPeriod();
        var at = period.start().plusSeconds(1);
        stored(at, TransferStatus.PUBLISHED, null, null);
        stored(at, TransferStatus.PUBLISHED, null, null);
        stored(at, TransferStatus.POSTED, null, at.plusSeconds(1));
        stored(at, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT, at.plusSeconds(1));
        stored(at, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS, at.plusSeconds(1));
        stored(at, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS, at.plusSeconds(1));
        stored(period.end(), TransferStatus.POSTED, null, period.end().plusSeconds(1));
        stored(period.start().minus(1, ChronoUnit.MICROS), TransferStatus.POSTED, null, period.start());

        var summary = summary(period);

        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody()).isEqualTo(Map.of(
            "periodStart", period.start().toString(),
            "accepted", 6,
            "published", 6,
            "posted", 1,
            "rejected", 3,
            "rejectionsByReason", Map.of(RejectReason.BLOCKED_ACCOUNT.name(), 1, RejectReason.INSUFFICIENT_FUNDS.name(), 2)));
    }

    @Test
    void a_period_without_transfers_closes_with_no_entries_and_has_zeros_in_its_summary() {
        var period = lastEndedPeriod();

        var closed = close(period);

        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().keySet()).containsExactlyInAnyOrder("periodStart", "closedAt", "entryCount");
        assertThat(closed.getBody()).containsEntry("periodStart", period.start().toString()).containsEntry("entryCount", 0);
        assertThat(summary(period).getBody()).containsEntry("accepted", 0).containsEntry("rejectionsByReason", Map.of());
    }

    @Test
    void closing_twice_gives_the_first_answer_even_if_a_row_appeared_in_the_period_since() {
        var period = lastEndedPeriod();
        acceptedAt(period.start());

        var first = close(period);
        acceptedAt(period.start().plusSeconds(1));
        var second = close(period);

        assertThat(first.getBody()).containsEntry("entryCount", 1);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM periods", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a period is closed only after it ended: the current one, a future one and one off the grid are unknown")
    void a_period_is_closed_only_after_it_ended_the_current_one_a_future_one_and_one_off_the_grid_are_unknown() {
        var current = currentPeriod();
        var offGrid = new Period(lastEndedPeriod().start().plusSeconds(1), lastEndedPeriod().end().plusSeconds(1));

        for (var period : new Period[] {current, current.next(), offGrid}) {
            var refused = close(period);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(refused.getBody().get("error")).isEqualTo(GatewayErrors.UNKNOWN_PERIOD);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM periods", Long.class)).isZero();
    }

    @Test
    void a_period_older_than_the_retention_is_unknown_and_the_oldest_one_still_held_is_not() {
        var oldestHeld = Period.containing(Instant.now().minus(props.periods().retention()), props.periods().length());

        assertThat(summary(oldestHeld).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary(oldestHeld.previous()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(close(oldestHeld.previous()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(summary(currentPeriod()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary(currentPeriod().next()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("close waits for an insert in flight, so that insert is counted")
    void close_waits_for_an_insert_in_flight_so_that_insert_is_counted() throws Exception {
        var period = lastEndedPeriod();
        try (var insert = dataSource.getConnection()) {
            insert.setAutoCommit(false);
            holdAcceptLockShared(insert);
            insertUncommitted(insert, period.end().minus(1, ChronoUnit.MICROS));

            var closing = CompletableFuture.supplyAsync(() -> close(period));
            Thread.sleep(300);
            assertThat(closing).isNotDone();
            insert.commit();

            assertThat(closing.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(closing.get().getBody()).containsEntry("entryCount", 1);
        }
    }

    @Test
    void a_submission_arriving_while_the_close_waits_is_accepted_after_it_into_the_current_period() throws Exception {
        var period = lastEndedPeriod();
        var late = body();
        try (var insert = dataSource.getConnection()) {
            insert.setAutoCommit(false);
            holdAcceptLockShared(insert);
            var waitingBefore = waitingAdvisoryLocks();

            var closing = CompletableFuture.supplyAsync(() -> close(period));
            await().atMost(Duration.ofMillis(800)).until(() -> waitingAdvisoryLocks() > waitingBefore);
            var submitting = CompletableFuture.supplyAsync(() -> submit(late));
            await().atMost(Duration.ofMillis(800)).until(() -> waitingAdvisoryLocks() > waitingBefore + 1);
            insert.commit();

            var closed = closing.get();
            var accepted = submitting.get();
            assertThat(closed.getBody()).containsEntry("entryCount", 0);
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(Instant.parse((String) accepted.getBody().get("acceptedAt"))).isAfterOrEqualTo(period.end());
        }
    }

    @Test
    void close_gives_up_with_503_when_an_insert_in_flight_outlasts_the_lock_timeout_and_succeeds_when_asked_again() throws SQLException {
        var period = lastEndedPeriod();
        try (var insert = dataSource.getConnection()) {
            insert.setAutoCommit(false);
            holdAcceptLockShared(insert);

            var timedOut = close(period);

            assertThat(timedOut.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(timedOut.getBody().get("error")).isEqualTo(GatewayErrors.CLOSE_LOCK_TIMEOUT);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM periods", Long.class)).isZero();
            insert.rollback();
        }
        assertThat(close(period).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private int waitingAdvisoryLocks() throws SQLException {
        try (var c = dataSource.getConnection();
            var st = c.createStatement();
            var rs = st.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void holdAcceptLockShared(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))")) {
            statement.setString(1, PeriodRepository.ACCEPT_LOCK);
            statement.executeQuery().close();
        }
    }

    private static void insertUncommitted(Connection connection, Instant acceptedAt) throws SQLException {
        try (var statement = connection.prepareStatement(
            "INSERT INTO transfers (accepted_at, transfer_id, from_account, to_account, amount_cents, status, published_at) VALUES (?, ?, 1, 2, 100, 'PUBLISHED', ?)")) {
            statement.setTimestamp(1, Timestamp.from(acceptedAt));
            statement.setObject(2, UUID.randomUUID());
            statement.setTimestamp(3, Timestamp.from(acceptedAt));
            statement.executeUpdate();
        }
    }
}
