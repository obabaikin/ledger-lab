package com.ledgerlab.ledger.service

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.AccountStatus
import com.ledgerlab.common.RejectReason
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.persistence.AccountRepository
import com.ledgerlab.ledger.persistence.JournalRepository
import com.ledgerlab.ledger.persistence.TransferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerService(
    private val accounts: AccountRepository,
    private val transfers: TransferRepository,
    private val journal: JournalRepository,
) {

    /**
     * One transfer is one transaction; a repeated delivery of the same command is a no-op,
     * also when two consumers process it at the same time.
     */
    @Transactional
    fun apply(command: TransferCommand): TransferResult {
        // Everything the database would refuse must be rejected here: the consumer retries
        // constraint violations forever, which would stall the partition.
        if (command.amountCents <= 0) {
            return reject(command, RejectReason.INVALID_AMOUNT)
        }
        if (command.fromAccount == command.toAccount) {
            return reject(command, RejectReason.SAME_ACCOUNT)
        }

        // Lock both rows in ascending account order so that concurrent A->B and B->A
        // transfers wait for each other instead of deadlocking.
        val (lowerNo, higherNo) = listOf(command.fromAccount, command.toAccount).sorted()
        val lower = accounts.lock(command.runId, lowerNo) ?: return reject(command, RejectReason.UNKNOWN_ACCOUNT)
        val higher = accounts.lock(command.runId, higherNo) ?: return reject(command, RejectReason.UNKNOWN_ACCOUNT)
        val from = if (lower.accountNo == command.fromAccount) lower else higher
        val to = if (from === lower) higher else lower

        rejectReasonFor(from, to, command.amountCents)?.let { return reject(command, it) }

        if (!transfers.insertIfAbsent(command, rejectReason = null)) {
            return TransferResult.AlreadyProcessed
        }
        journal.insert(command.runId, command.transferId, from.accountNo, -command.amountCents)
        journal.insert(command.runId, command.transferId, to.accountNo, command.amountCents)
        accounts.debit(command.runId, from.accountNo, command.amountCents)
        accounts.credit(command.runId, to.accountNo, command.amountCents)
        return TransferResult.Posted
    }

    private fun rejectReasonFor(from: Account, to: Account, amountCents: Long): RejectReason? = when {
        from.status == AccountStatus.BLOCKED || to.status == AccountStatus.BLOCKED -> RejectReason.BLOCKED_ACCOUNT
        from.balanceCents < amountCents -> RejectReason.INSUFFICIENT_FUNDS
        from.dailySpentCents + amountCents > from.dailyLimitCents -> RejectReason.DAILY_LIMIT_EXCEEDED
        else -> null
    }

    private fun reject(command: TransferCommand, reason: RejectReason): TransferResult =
        if (transfers.insertIfAbsent(command, reason)) TransferResult.Rejected(reason) else TransferResult.AlreadyProcessed
}
