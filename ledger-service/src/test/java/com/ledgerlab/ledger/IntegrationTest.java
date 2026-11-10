package com.ledgerlab.ledger;

import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.TransferResult;
import com.ledgerlab.ledger.service.AccountSetup;
import com.ledgerlab.ledger.service.DeferredCreditApplier;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.RunService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
abstract class IntegrationTest {

    protected static final TransferResult POSTED = new TransferResult.Posted();
    protected static final TransferResult ALREADY_PROCESSED = new TransferResult.AlreadyProcessed();

    private static final long DEFAULT_OPENING_BALANCE_CENTS = 100_000;
    private static final long DEFAULT_DAILY_LIMIT_CENTS = Long.MAX_VALUE / 2;

    @Autowired private DeferredCreditApplier creditApplier;
    @Autowired private JdbcClient creditJdbc;

    protected static TransferResult rejected(RejectReason reason) {
        return new TransferResult.Rejected(reason);
    }

    protected static TransferResult applyOne(LedgerService ledger, TransferCommand command) {
        return ledger.applyBatch(java.util.List.of(command)).getFirst();
    }

    /** The scheduled applier may hold the lock, so this waits until the run has no pending credit. */
    protected void awaitCreditsApplied(UUID runId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            creditApplier.applyPending();
            return pendingCredits(runId) == 0L;
        });
    }

    protected long pendingCredits(UUID runId) {
        return creditJdbc.sql("SELECT count(*) FROM deferred_credits WHERE run_id = :runId")
            .param("runId", runId).query(Long.class).single();
    }

    protected static Map<Integer, Long> balances(RunService runs, UUID runId) {
        return runs.listAccounts(runId).stream().collect(Collectors.toMap(Account::accountNo, Account::balanceCents));
    }

    protected static TransferCommand transfer(UUID runId, int from, int to, long amountCents) {
        return new TransferCommand(runId, UUID.randomUUID(), from, to, amountCents, Instant.now());
    }

    protected static AccountSetup setup(int accountCount) {
        return setup(accountCount, DEFAULT_OPENING_BALANCE_CENTS);
    }

    protected static AccountSetup setup(int accountCount, long openingBalanceCents) {
        return setup(accountCount, openingBalanceCents, 0);
    }

    protected static AccountSetup setup(int accountCount, long openingBalanceCents, int blockedAccounts) {
        return new AccountSetup(accountCount, openingBalanceCents, blockedAccounts, DEFAULT_DAILY_LIMIT_CENTS);
    }

    protected static AccountSetup setupWithDailyLimit(int accountCount, long openingBalanceCents, long dailyLimitCents) {
        return new AccountSetup(accountCount, openingBalanceCents, 0, dailyLimitCents);
    }
}
