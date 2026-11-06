package com.ledgerlab.ledger

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.service.AccountSetup
import com.ledgerlab.ledger.service.DeferredCreditApplier
import com.ledgerlab.ledger.service.LedgerService
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
abstract class IntegrationTest {

    @Autowired private lateinit var creditApplier: DeferredCreditApplier
    @Autowired private lateinit var creditJdbc: JdbcClient

    protected fun LedgerService.applyOne(command: TransferCommand): TransferResult = applyBatch(listOf(command)).single()

    /** The scheduled applier may hold the lock, so this waits until the run has no pending credit. */
    protected fun awaitCreditsApplied(runId: UUID) {
        await().atMost(Duration.ofSeconds(10)).until {
            creditApplier.applyPending()
            pendingCredits(runId) == 0L
        }
    }

    protected fun pendingCredits(runId: UUID): Long =
        creditJdbc.sql("SELECT count(*) FROM deferred_credits WHERE run_id = :runId")
            .param("runId", runId).query(Long::class.java).single()

    protected fun transfer(runId: UUID, from: Int, to: Int, amountCents: Long, transferId: UUID = UUID.randomUUID()) =
        TransferCommand(runId, transferId, from, to, amountCents, Instant.now())

    protected fun setup(
        accountCount: Int = 10,
        openingBalanceCents: Long = 100_000,
        blockedAccounts: Int = 0,
        dailyLimitCents: Long = Long.MAX_VALUE / 2,
    ) = AccountSetup(accountCount, openingBalanceCents, blockedAccounts, dailyLimitCents)
}
