package com.ledgerlab.ledger.service

import com.ledgerlab.common.RejectReason
import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.AccountKey
import com.ledgerlab.ledger.domain.AccountStatus
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.persistence.AccountRepository
import com.ledgerlab.ledger.persistence.DeferredCreditRepository
import com.ledgerlab.ledger.persistence.JournalEntry
import com.ledgerlab.ledger.persistence.JournalRepository
import com.ledgerlab.ledger.persistence.TransferKey
import com.ledgerlab.ledger.persistence.TransferRepository
import com.ledgerlab.ledger.persistence.TransferRow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class ConcurrentPostingException(command: TransferCommand) :
    IllegalStateException("Transfer ${command.transferId} of run ${command.runId} was recorded concurrently; the batch is retried")

@Service
class LedgerService(
    private val accounts: AccountRepository,
    private val transfers: TransferRepository,
    private val journal: JournalRepository,
    private val deferredCredits: DeferredCreditRepository,
) {

    /**
     * One batch is one transaction; results come in the order of the commands. A repeated delivery of the same
     * command is a no-op, also when two consumers process it at the same time.
     */
    @Transactional
    fun applyBatch(commands: List<TransferCommand>): List<TransferResult> {
        if (commands.isEmpty()) return emptyList()
        // Everything the database would refuse must be rejected here: the consumer retries
        // database errors forever, which would stall the partition.
        val movable = commands.filter { it.amountCents > 0 && it.fromAccount != it.toAccount }
        val batch = BatchAccounts(
            senders = accounts.lockAll(movable.map { it.fromKey() }.toSet()).associateByTo(HashMap()) { it.key() },
            receivers = accounts.findAll(movable.map { it.toKey() }.toSet()).associateBy { it.key() },
        )
        // After the lock: a consumer posting the same transfer holds the same sender, so its commit is visible now.
        val seen = transfers.findRecorded(commands.map { it.key() }.toSet()).toMutableSet()

        val results = commands.map { command ->
            if (!seen.add(command.key())) return@map TransferResult.AlreadyProcessed
            val reason = rejectReasonFor(command, batch)
            if (reason == null) post(command, batch)
            reason?.let { TransferResult.Rejected(it) } ?: TransferResult.Posted
        }
        val inserted = persist(commands.zip(results), batch)
        // A rejection someone else recorded meanwhile is theirs to report, as with a duplicate found up front.
        return commands.zip(results).map { (command, result) ->
            if (result is TransferResult.Rejected && command.key() !in inserted) TransferResult.AlreadyProcessed else result
        }
    }

    private class BatchAccounts(val senders: MutableMap<AccountKey, Account>, val receivers: Map<AccountKey, Account>) {
        val credits = HashMap<AccountKey, Long>()
    }

    private fun rejectReasonFor(command: TransferCommand, batch: BatchAccounts): RejectReason? {
        if (command.amountCents <= 0) return RejectReason.INVALID_AMOUNT
        if (command.fromAccount == command.toAccount) return RejectReason.SAME_ACCOUNT
        val from = batch.senders[command.fromKey()] ?: return RejectReason.UNKNOWN_ACCOUNT
        val to = batch.receivers[command.toKey()] ?: return RejectReason.UNKNOWN_ACCOUNT
        return when {
            from.status == AccountStatus.BLOCKED || to.status == AccountStatus.BLOCKED -> RejectReason.BLOCKED_ACCOUNT
            from.balanceCents < command.amountCents -> RejectReason.INSUFFICIENT_FUNDS
            from.dailySpentCents + command.amountCents > from.dailyLimitCents -> RejectReason.DAILY_LIMIT_EXCEEDED
            else -> null
        }
    }

    private fun post(command: TransferCommand, batch: BatchAccounts) {
        batch.senders.computeIfPresent(command.fromKey()) { _, from ->
            from.copy(
                balanceCents = from.balanceCents - command.amountCents,
                dailySpentCents = from.dailySpentCents + command.amountCents,
            )
        }
        // Deferred also when the receiver sends in this batch: the applier adds it later, adding it here would count it twice.
        batch.credits.merge(command.toKey(), command.amountCents, Long::plus)
    }

    private fun persist(outcomes: List<Pair<TransferCommand, TransferResult>>, batch: BatchAccounts): Set<TransferKey> {
        val decided = outcomes.filter { (_, result) -> result != TransferResult.AlreadyProcessed }
        if (decided.isEmpty()) return emptySet()
        val inserted = transfers.insertAllIfAbsent(
            decided.map { (command, result) -> TransferRow(command, (result as? TransferResult.Rejected)?.reason) },
        )
        val posted = decided.filter { (_, result) -> result == TransferResult.Posted }.map { (command, _) -> command }
        // Posting it anyway would post it twice; the retry of the batch finds it recorded.
        posted.firstOrNull { it.key() !in inserted }?.let { throw ConcurrentPostingException(it) }
        journal.insertAll(
            posted.flatMap {
                listOf(
                    JournalEntry(it.runId, it.transferId, it.fromAccount, -it.amountCents),
                    JournalEntry(it.runId, it.transferId, it.toAccount, it.amountCents),
                )
            },
        )
        accounts.updateBalances(posted.map { it.fromKey() }.toSet().map { batch.senders.getValue(it) })
        deferredCredits.insertAll(batch.credits)
        return inserted
    }

    private fun Account.key() = AccountKey(runId, accountNo)
    private fun TransferCommand.key() = TransferKey(runId, transferId)
    private fun TransferCommand.fromKey() = AccountKey(runId, fromAccount)
    private fun TransferCommand.toKey() = AccountKey(runId, toAccount)
}
