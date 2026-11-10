package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.AccountStatus;
import com.ledgerlab.ledger.domain.ReconciliationReport;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.persistence.DeferredCreditRepository;
import com.ledgerlab.ledger.persistence.ReconciliationRepository;
import com.ledgerlab.ledger.persistence.TransferRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final ReconciliationRepository reconciliation;
    private final DeferredCreditRepository deferredCredits;

    public RunService(
        AccountRepository accounts,
        TransferRepository transfers,
        ReconciliationRepository reconciliation,
        DeferredCreditRepository deferredCredits
    ) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.reconciliation = reconciliation;
        this.deferredCredits = deferredCredits;
    }

    /** Account numbers start at 1; the last {@link AccountSetup#blockedAccounts} are blocked. */
    @Transactional
    public List<Account> openAccounts(UUID runId, AccountSetup setup) {
        var firstBlocked = setup.accountCount() - setup.blockedAccounts() + 1;
        var created = IntStream.rangeClosed(1, setup.accountCount())
            .mapToObj(no -> new Account(
                runId,
                no,
                setup.openingBalanceCents(),
                setup.openingBalanceCents(),
                no >= firstBlocked ? AccountStatus.BLOCKED : AccountStatus.ACTIVE,
                setup.dailyLimitCents(),
                0))
            .toList();
        try {
            accounts.insertAll(created);
        } catch (DuplicateKeyException e) {
            // The primary key, not a pre-check, decides who created the run first.
            throw new RunAlreadyExistsException(runId);
        }
        return created;
    }

    public List<Account> listAccounts(UUID runId) {
        return accounts.findAll(runId);
    }

    // REPEATABLE_READ gives all reconciliation queries one snapshot, so a run that is
    // still receiving transfers cannot show a false mismatch between the queries.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile(UUID runId) {
        return reconciliation.reconcile(runId);
    }

    /** Journal entries go with their transfers and accounts via ON DELETE CASCADE. */
    @Transactional
    public boolean deleteRun(UUID runId) {
        // Pending credits before accounts, the applier's order: the cascade from the accounts would take them after.
        deferredCredits.deleteRun(runId);
        transfers.deleteRun(runId);
        return accounts.deleteRun(runId) > 0;
    }
}
