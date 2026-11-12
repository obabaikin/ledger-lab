package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.ledger.domain.AccountMismatch;
import com.ledgerlab.ledger.domain.ReconciliationCheck;
import com.ledgerlab.ledger.domain.TransferKind;
import com.ledgerlab.ledger.service.AccountOpening;
import com.ledgerlab.ledger.service.AccountSetup;
import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The check of a period on the production's number of accounts, with statistics gathered before either snapshot it
 * compares: as on a server where autoanalyze has not yet reached them.
 */
class PeriodCheckAtScaleTest extends IntegrationTest {

    private static final int ACCOUNTS = 100_000;
    private static final long OPENING_BALANCE_CENTS = 1_000;
    private static final Duration CHECK_LIMIT = Duration.ofSeconds(10);
    private static final int DRIFTED_ACCOUNT = 50_000;

    @Autowired AccountOpening accountOpening;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void autoanalyzeSnapshotsAgain() {
        jdbc.sql("ALTER TABLE account_snapshots RESET (autovacuum_enabled)").update();
    }

    @Test
    void checks_a_hundred_thousand_accounts_within_ten_seconds_on_statistics_that_know_neither_snapshot_and_finds_the_drifted_one() {
        accountOpening.open(new AccountSetup(ACCOUNTS, OPENING_BALANCE_CENTS, 0, POOL_OPENING_BALANCE_CENTS));
        jdbc.sql("INSERT INTO account_snapshots (period_start, account_no, balance_cents) SELECT :start, account_no, balance_cents FROM accounts")
            .param("start", Timestamp.from(period.start().minus(periodsProperties.snapshotRetention().dividedBy(2))))
            .update();
        jdbc.sql("ALTER TABLE account_snapshots SET (autovacuum_enabled = off)").update();
        jdbc.sql("ANALYZE account_snapshots").update();
        jdbc.sql("ANALYZE journal_entries").update();
        jdbc.sql("ANALYZE transfers").update();
        periodRepository.snapshotBalancesAtEndOf(period.previous());
        everyAccountPaysTheNextOneACent();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE account_no = :account").param("account", DRIFTED_ACCOUNT).update();

        var started = System.nanoTime();
        var check = check();
        var took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(CHECK_LIMIT);
        assertThat(check.accountsCompared()).isTrue();
        assertThat(check.journalEntries()).isEqualTo(2L * ACCOUNTS);
        assertThat(check.failedChecks()).containsExactly(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
        assertThat(check.accountsOutOfBalance()).isEqualTo(1);
        assertThat(check.accountExamples()).containsExactly(new AccountMismatch(DRIFTED_ACCOUNT, 0, 1));
    }

    /** Posted as the ledger would post it, without its cost: every balance ends where it began. */
    private void everyAccountPaysTheNextOneACent() {
        jdbc.sql(
            """
            WITH posted AS (
                SELECT n AS from_account, n % :accounts + 1 AS to_account, gen_random_uuid() AS transfer_id,
                       CAST(:start AS timestamptz) + n * interval '1 microsecond' AS accepted_at
                FROM generate_series(1, :accounts) n
            ), logged AS (
                INSERT INTO transfers (accepted_at, transfer_id, kind, from_account, to_account, amount_cents, outcome, result_published_at)
                SELECT accepted_at, transfer_id, :kind, from_account, to_account, 1, :posted, now() FROM posted
            )
            INSERT INTO journal_entries (accepted_at, transfer_id, account_no, amount_cents)
            SELECT accepted_at, transfer_id, from_account, -1 FROM posted
            UNION ALL
            SELECT accepted_at, transfer_id, to_account, 1 FROM posted
            """)
            .param("accounts", ACCOUNTS)
            .param("start", Timestamp.from(period.start()))
            .param("kind", TransferKind.CLIENT.name())
            .param("posted", TransferOutcome.POSTED.name())
            .update();
    }
}
