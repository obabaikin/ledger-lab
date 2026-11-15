package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.ledger.persistence.PoolRepository;
import com.ledgerlab.ledger.persistence.ResultOutboxRepository;
import com.ledgerlab.ledger.pool.PoolProperties;
import com.ledgerlab.ledger.pool.PoolService;
import com.ledgerlab.ledger.service.AccountSetup;
import com.ledgerlab.ledger.service.LedgerService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The test bank has 10 accounts, so the round of 24 hours comes to one account every 2 hours 24 minutes. */
class PoolTest extends IntegrationTest {

    private static final int ACCOUNTS = 10;
    private static final long OPENING = 1_000;

    @Autowired PoolService pool;
    @Autowired PoolProperties props;
    @Autowired PoolRepository poolRepository;
    @Autowired ResultOutboxRepository outbox;
    @Autowired LedgerService ledger;
    @Autowired MeterRegistry registry;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate tx;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired ObjectMapper objectMapper;
    @Value("${ledger.results.topic}") String resultsTopic;

    private Duration perAccount() {
        return props.round().dividedBy(ACCOUNTS);
    }

    /** As if the round last moved, and the hot accounts were last swept, this long before {@code now}. */
    private void lastMoved(Instant now, Duration roundAgo, Duration sweepAgo) {
        jdbc.sql("UPDATE pool_state SET round_advanced_at = :round, hot_swept_at = :sweep")
            .param("round", Timestamp.from(now.minus(roundAgo)))
            .param("sweep", Timestamp.from(now.minus(sweepAgo)))
            .update();
    }

    private Instant aWholeRoundIsDue() {
        var now = period.start().plusSeconds(60);
        lastMoved(now, props.round(), Duration.ZERO);
        return now;
    }

    private double count(String metric) {
        return registry.get(metric).counter().count();
    }

    private void pay(int from, int to, long amountCents) {
        assertThat(applyOne(ledger, transfer(from, to, amountCents))).isEqualTo(POSTED);
    }

    @Test
    void a_round_brings_every_account_back_to_its_opening_balance_and_the_sum_of_all_balances_stays_what_it_was() {
        openBank(ACCOUNTS, OPENING);
        pay(3, 1, 400);
        pay(4, 2, 300);
        pay(5, 1, 100);
        awaitCreditsApplied();

        var looked = pool.advance(aWholeRoundIsDue());

        assertThat(looked).isEqualTo(ACCOUNTS);
        assertThat(balances().values()).containsOnly(OPENING);
        assertThat(poolBalance()).isEqualTo(POOL_OPENING_BALANCE_CENTS);
        assertThat(accountRepository.books().balanceTotalCents()).isEqualTo(ACCOUNTS * OPENING + POOL_OPENING_BALANCE_CENTS);
        assertTheBooksAgree();
    }

    @Test
    void a_posting_of_the_pool_is_a_double_entry_of_the_period_but_no_transfer_of_a_client() {
        openBank(ACCOUNTS, OPENING);
        var paid = transfer(3, 1, 400);
        applyOne(ledger, paid);
        awaitCreditsApplied();
        var postingsBefore = count(PoolService.POSTINGS_METRIC);

        pool.advance(aWholeRoundIsDue());

        assertThat(count(PoolService.POSTINGS_METRIC) - postingsBefore).isEqualTo(2);
        var counted = reconciliation();
        assertThat(counted.poolPostings()).isEqualTo(2);
        assertThat(counted.postedTransfers()).isEqualTo(1);
        assertThat(check().journalEntries()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM transfers WHERE kind = 'POOL' AND result_published_at IS NULL").query(Long.class).single()).isZero();
        List<TransferResultEvent> waiting = tx.execute(status -> outbox.claimUnpublished(100));
        assertThat(waiting).extracting(TransferResultEvent::transferId).doesNotContainAnyElementsOf(poolTransferIds());
    }

    @Test
    void no_result_event_is_published_for_a_posting_of_the_pool() {
        openBank(ACCOUNTS, OPENING);
        var received = new ArrayList<UUID>();
        var overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = consumerFactory.createConsumer("pool-test-" + UUID.randomUUID(), null, null, overrides)) {
            consumer.subscribe(List.of(resultsTopic));
            pay(3, 1, 400);
            awaitCreditsApplied();
            pool.advance(aWholeRoundIsDue());
            // Published after the pool's postings: once it has arrived, theirs would have arrived too.
            var after = transfer(4, 2, 10);
            applyOne(ledger, after);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record ->
                    received.add(objectMapper.readValue(record.value(), TransferResultEvent.class).transferId()));
                assertThat(received).contains(after.transferId());
            });
        }

        assertThat(poolTransferIds()).hasSize(2);
        assertThat(received).doesNotContainAnyElementsOf(poolTransferIds());
    }

    private List<UUID> poolTransferIds() {
        return jdbc.sql("SELECT transfer_id FROM transfers WHERE kind = 'POOL'").query(UUID.class).list();
    }

    @Test
    void the_round_keeps_its_pace_by_the_time_passed_however_often_it_is_asked() {
        openBank(ACCOUNTS, OPENING);
        var start = period.start();
        // The hot accounts are not due in the hours this test walks through.
        lastMoved(start, Duration.ZERO, props.round().negated());

        assertThat(pool.advance(start.plus(perAccount()).minusSeconds(1))).isZero();
        assertThat(pool.advance(start.plus(perAccount().multipliedBy(2)).plusSeconds(1))).isEqualTo(2);
        assertThat(pool.advance(start.plus(perAccount().multipliedBy(2)).plusSeconds(2))).isZero();
        assertThat(pool.advance(start.plus(perAccount().multipliedBy(3)))).isEqualTo(1);

        assertThat(poolRepository.state().nextAccountNo()).isEqualTo(4);
    }

    @Test
    void after_the_last_account_the_round_goes_on_with_the_first() {
        openBank(ACCOUNTS, OPENING);
        var now = period.start();
        jdbc.sql("UPDATE pool_state SET next_account_no = :next").param("next", ACCOUNTS - 1).update();
        lastMoved(now, perAccount().multipliedBy(3), Duration.ZERO);
        pay(1, 2, 100);
        awaitCreditsApplied();

        assertThat(pool.advance(now)).isEqualTo(3);

        assertThat(poolRepository.state().nextAccountNo()).isEqualTo(2);
        assertThat(balances()).containsEntry(1, OPENING).containsEntry(2, OPENING + 100);
    }

    @Test
    void a_round_far_behind_goes_on_from_now_instead_of_catching_up() {
        openBank(ACCOUNTS, OPENING);
        var now = period.start();
        lastMoved(now, perAccount().multipliedBy(props.maxBatch() + 50L), Duration.ZERO);

        assertThat(pool.advance(now)).isEqualTo(props.maxBatch());

        assertThat(poolRepository.state().roundAdvancedAt()).isEqualTo(now);
        assertThat(pool.advance(now.plusSeconds(1))).isZero();
    }

    @Test
    void while_another_instance_moves_the_pool_this_one_does_nothing() throws SQLException {
        openBank(ACCOUNTS, OPENING);
        pay(3, 1, 400);
        awaitCreditsApplied();
        var now = aWholeRoundIsDue();

        try (var other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var lock = other.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended('ledger.pool', 0))")) {
                lock.executeQuery().close();
            }

            assertThat(pool.advance(now)).isZero();
            assertThat(balances()).containsEntry(1, OPENING + 400);

            other.rollback();
        }

        assertThat(pool.advance(now)).isEqualTo(ACCOUNTS);
        assertThat(pool.advance(now)).isZero();
        assertThat(poolTransferIds()).hasSize(2);
    }

    @Test
    void an_account_the_pool_cannot_cover_is_left_as_it_is_and_counted_and_the_pool_never_goes_below_zero() {
        openBank(new AccountSetup(ACCOUNTS, OPENING, 0, 50));
        pay(3, 4, 100);
        awaitCreditsApplied();
        var skippedBefore = count(PoolService.SKIPPED_METRIC);

        // Account 3, 100 short, comes before account 4 and its surplus of 100; the pool holds 50.
        pool.advance(aWholeRoundIsDue());

        assertThat(count(PoolService.SKIPPED_METRIC) - skippedBefore).isEqualTo(1);
        assertThat(balances()).containsEntry(3, OPENING - 100).containsEntry(4, OPENING);
        assertThat(poolBalance()).isEqualTo(150);
        assertThat(accountRepository.books().balanced()).isTrue();

        pool.advance(aWholeRoundIsDue());

        assertThat(balances().values()).containsOnly(OPENING);
        assertThat(poolBalance()).isEqualTo(50);
    }

    @Test
    void once_per_interval_the_surplus_of_the_hot_accounts_goes_to_the_pool_and_a_hot_account_that_is_short_gets_nothing() {
        openBank(ACCOUNTS, OPENING);
        pay(2, 1, 300);
        pay(3, 5, 40);
        awaitCreditsApplied();
        var now = period.start().plusSeconds(60);
        lastMoved(now, Duration.ZERO, props.hotSweepInterval());

        assertThat(pool.advance(now)).isEqualTo(props.hotAccounts());

        assertThat(balances()).isEqualTo(Map.of(
            1, OPENING, 2, OPENING - 300, 3, OPENING - 40, 4, OPENING, 5, OPENING + 40,
            6, OPENING, 7, OPENING, 8, OPENING, 9, OPENING, 10, OPENING));
        assertThat(poolBalance()).isEqualTo(POOL_OPENING_BALANCE_CENTS + 300);
        assertThat(pool.advance(now.plus(props.hotSweepInterval()).minusSeconds(1))).isZero();
    }

    @Test
    void a_blocked_account_is_left_alone() {
        openBank(ACCOUNTS, OPENING, 1);
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 5 WHERE account_no = :no").param("no", ACCOUNTS).update();

        pool.advance(aWholeRoundIsDue());

        assertThat(balances()).containsEntry(ACCOUNTS, OPENING + 5);
        assertThat(poolTransferIds()).isEmpty();
    }
}
