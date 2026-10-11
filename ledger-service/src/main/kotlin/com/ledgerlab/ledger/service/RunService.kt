package com.ledgerlab.ledger.service

import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.AccountStatus
import com.ledgerlab.ledger.domain.ReconciliationReport
import com.ledgerlab.ledger.persistence.AccountRepository
import com.ledgerlab.ledger.persistence.ReconciliationRepository
import com.ledgerlab.ledger.persistence.TransferRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class AccountSetup(
    val accountCount: Int,
    val openingBalanceCents: Long,
    val blockedAccounts: Int,
    val dailyLimitCents: Long,
) {
    init {
        require(accountCount >= MIN_ACCOUNTS) { "accountCount must be at least $MIN_ACCOUNTS" }
        require(blockedAccounts in 0..<accountCount) { "blockedAccounts must leave at least one active account" }
        require(openingBalanceCents >= 0) { "openingBalanceCents must not be negative" }
        require(dailyLimitCents >= 0) { "dailyLimitCents must not be negative" }
    }

    companion object {
        /** A transfer needs a sender and a different receiver. */
        const val MIN_ACCOUNTS = 2L
        const val MAX_ACCOUNTS = 10_000L
    }
}

class RunAlreadyExistsException(runId: UUID) : RuntimeException("Run $runId already has accounts")

@Service
class RunService(
    private val accounts: AccountRepository,
    private val transfers: TransferRepository,
    private val reconciliation: ReconciliationRepository,
) {

    /** Creates the account set of a run. Account numbers start at 1; the last [AccountSetup.blockedAccounts] are blocked. */
    @Transactional
    fun openAccounts(runId: UUID, setup: AccountSetup): List<Account> {
        val firstBlocked = setup.accountCount - setup.blockedAccounts + 1
        val created = (1..setup.accountCount).map { no ->
            Account(
                runId = runId,
                accountNo = no,
                openingBalanceCents = setup.openingBalanceCents,
                balanceCents = setup.openingBalanceCents,
                status = if (no >= firstBlocked) AccountStatus.BLOCKED else AccountStatus.ACTIVE,
                dailyLimitCents = setup.dailyLimitCents,
                dailySpentCents = 0,
            )
        }
        try {
            accounts.insertAll(created)
        } catch (e: DuplicateKeyException) {
            // The primary key, not a pre-check, decides who created the run first.
            throw RunAlreadyExistsException(runId)
        }
        return created
    }

    fun listAccounts(runId: UUID): List<Account> = accounts.findAll(runId)

    // REPEATABLE_READ gives all reconciliation queries one snapshot, so a run that is
    // still receiving transfers cannot show a false mismatch between the queries.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun reconcile(runId: UUID): ReconciliationReport = reconciliation.reconcile(runId)

    /** Journal entries go with their transfers and accounts via ON DELETE CASCADE. */
    @Transactional
    fun deleteRun(runId: UUID): Boolean {
        transfers.deleteRun(runId)
        return accounts.deleteRun(runId) > 0
    }
}
