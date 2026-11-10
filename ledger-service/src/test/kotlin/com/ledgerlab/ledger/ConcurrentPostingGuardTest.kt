package com.ledgerlab.ledger

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.AccountStatus
import com.ledgerlab.ledger.persistence.AccountRepository
import com.ledgerlab.ledger.persistence.DeferredCreditRepository
import com.ledgerlab.ledger.persistence.JournalRepository
import com.ledgerlab.ledger.persistence.TransferRepository
import com.ledgerlab.ledger.service.ConcurrentPostingException
import com.ledgerlab.ledger.service.LedgerService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anySet
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.Instant
import java.util.UUID

class ConcurrentPostingGuardTest {

    private val accounts = mock(AccountRepository::class.java)
    private val transfers = mock(TransferRepository::class.java)
    private val journal = mock(JournalRepository::class.java)
    private val deferredCredits = mock(DeferredCreditRepository::class.java)
    private val ledger = LedgerService(accounts, transfers, journal, deferredCredits)

    @Test
    fun `a transfer someone else recorded after the check for duplicates is not posted a second time`() {
        val runId = UUID.randomUUID()
        val command = TransferCommand(runId, UUID.randomUUID(), 1, 2, 100, Instant.now())
        doReturn(listOf(account(runId, 1))).`when`(accounts).lockAll(anySet())
        doReturn(listOf(account(runId, 2))).`when`(accounts).findAll(anySet())
        doReturn(emptySet<Any>()).`when`(transfers).findRecorded(anySet())
        doReturn(emptySet<Any>()).`when`(transfers).insertAllIfAbsent(anyList())

        assertThatThrownBy { ledger.applyBatch(listOf(command)) }.isInstanceOf(ConcurrentPostingException::class.java)

        verify(journal, never()).insertAll(anyList())
        verify(accounts, never()).updateBalances(anyList())
        verify(deferredCredits, never()).insertAll(anyMap())
    }

    private fun account(runId: UUID, accountNo: Int) =
        Account(runId, accountNo, 1_000, 1_000, AccountStatus.ACTIVE, Long.MAX_VALUE / 2, 0)
}
