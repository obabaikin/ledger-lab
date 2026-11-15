package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.Period;
import com.ledgerlab.ledger.domain.PeriodCheck;
import com.ledgerlab.ledger.domain.PeriodReconciliation;
import com.ledgerlab.ledger.domain.TransferResult;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.persistence.PeriodRepository;
import com.ledgerlab.ledger.service.AccountOpening;
import com.ledgerlab.ledger.service.AccountSetup;
import com.ledgerlab.ledger.service.DeferredCreditApplier;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.PeriodChecker;
import com.ledgerlab.ledger.service.PeriodService;
import com.ledgerlab.ledger.service.PeriodsProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * There is one bank, so every test starts by emptying it and opens the accounts it needs. Its transfers are accepted
 * in the last period that has ended, the one a test can reconcile; the balances at that period's start are kept as
 * the snapshot the period's check compares with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(AwayFromTheHoursEnd.class)
abstract class IntegrationTest {

    protected static final TransferResult POSTED = new TransferResult.Posted();
    protected static final TransferResult ALREADY_PROCESSED = new TransferResult.AlreadyProcessed();
    protected static final TransferResult EXPIRED = new TransferResult.Expired();
    protected static final long POOL_OPENING_BALANCE_CENTS = 1_000_000;

    private static final long DEFAULT_OPENING_BALANCE_CENTS = 100_000;

    @Autowired protected AccountRepository accountRepository;
    @Autowired protected PeriodRepository periodRepository;
    @Autowired protected PeriodService periodService;
    @Autowired protected PeriodsProperties periodsProperties;
    @Autowired private AccountOpening accountOpening;
    @Autowired private DeferredCreditApplier creditApplier;
    @Autowired private PeriodChecker periodChecker;
    @Autowired private JdbcClient bankJdbc;

    protected Period period;
    private final AtomicLong accepted = new AtomicLong();

    @BeforeEach
    void emptyTheBank() {
        // One statement, one transaction each: nothing is held while waiting, so the background jobs cannot deadlock with it.
        for (var table : new String[] {"journal_entries", "deferred_credits", "transfers", "account_snapshots", "period_checks", "accounts"}) {
            bankJdbc.sql("DELETE FROM " + table).update();
        }
        bankJdbc.sql("UPDATE pool_state SET next_account_no = 1, round_advanced_at = now(), hot_swept_at = now()").update();
        period = Period.containing(Instant.now(), periodsProperties.length()).previous();
        accepted.set(0);
    }

    protected void openBank(int accountCount) {
        openBank(accountCount, DEFAULT_OPENING_BALANCE_CENTS);
    }

    protected void openBank(int accountCount, long openingBalanceCents) {
        openBank(accountCount, openingBalanceCents, 0);
    }

    protected void openBank(int accountCount, long openingBalanceCents, int blockedAccounts) {
        openBank(new AccountSetup(accountCount, openingBalanceCents, blockedAccounts, POOL_OPENING_BALANCE_CENTS));
    }

    protected void openBank(AccountSetup setup) {
        accountOpening.open(setup);
        periodRepository.snapshotBalancesAtEndOf(period.previous());
    }

    protected static TransferResult rejected(RejectReason reason) {
        return new TransferResult.Rejected(reason);
    }

    protected static TransferResult applyOne(LedgerService ledger, TransferCommand command) {
        return ledger.applyBatch(java.util.List.of(command)).getFirst();
    }

    /** Accepted in the test's period, each at its own microsecond. */
    protected TransferCommand transfer(int from, int to, long amountCents) {
        return transferAt(period.start().plus(accepted.incrementAndGet(), ChronoUnit.MICROS), from, to, amountCents);
    }

    protected static TransferCommand transferAt(Instant acceptedAt, int from, int to, long amountCents) {
        return new TransferCommand(UUID.randomUUID(), from, to, amountCents, acceptedAt);
    }

    /** The scheduled applier may hold the lock, so this waits until no credit is pending. */
    protected void awaitCreditsApplied() {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            creditApplier.applyPending();
            return pendingCredits() == 0L;
        });
    }

    protected long pendingCredits() {
        return bankJdbc.sql("SELECT count(*) FROM deferred_credits").query(Long.class).single();
    }

    /** Of the clients' accounts; the pool's is read with {@link #poolBalance()}. */
    protected Map<Integer, Long> balances() {
        return accountRepository.findAll().stream()
            .filter(account -> account.accountNo() >= Account.FIRST_CLIENT_ACCOUNT_NO)
            .collect(Collectors.toMap(Account::accountNo, Account::balanceCents));
    }

    protected long poolBalance() {
        return accountRepository.findAll().stream()
            .filter(account -> account.accountNo() == Account.POOL_ACCOUNT_NO)
            .findFirst().orElseThrow().balanceCents();
    }

    protected PeriodReconciliation reconciliation() {
        return periodService.reconciliation(period.start());
    }

    /** Makes the check of the test's period anew, whatever an earlier call in the test has kept. */
    protected PeriodCheck check() {
        return check(period);
    }

    protected PeriodCheck check(Period checked) {
        bankJdbc.sql("DELETE FROM period_checks WHERE period_start = :start").param("start", Timestamp.from(checked.start())).update();
        bankJdbc.sql("DELETE FROM account_snapshots WHERE period_start = :start").param("start", Timestamp.from(checked.start())).update();
        assertThat(periodChecker.check(checked)).isTrue();
        return periodRepository.findCheck(checked.start()).orElseThrow();
    }

    protected void assertTheBooksAgree() {
        var check = check();
        assertThat(check.accountsCompared()).isTrue();
        assertThat(check.failedChecks()).isEmpty();
        assertThat(accountRepository.books().balanced()).isTrue();
    }
}
