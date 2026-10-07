package com.ledgerlab.ledger

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.service.AccountSetup
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
abstract class IntegrationTest {

    protected fun transfer(runId: UUID, from: Int, to: Int, amountCents: Long, transferId: UUID = UUID.randomUUID()) =
        TransferCommand(runId, transferId, from, to, amountCents, Instant.now())

    protected fun setup(
        accountCount: Int = 10,
        openingBalanceCents: Long = 100_000,
        blockedAccounts: Int = 0,
        dailyLimitCents: Long = Long.MAX_VALUE / 2,
    ) = AccountSetup(accountCount, openingBalanceCents, blockedAccounts, dailyLimitCents)
}
