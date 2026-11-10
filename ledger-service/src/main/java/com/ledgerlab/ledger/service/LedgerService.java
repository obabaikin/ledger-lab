package com.ledgerlab.ledger.service;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.AccountKey;
import com.ledgerlab.ledger.domain.AccountStatus;
import com.ledgerlab.ledger.domain.TransferResult;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.persistence.DeferredCreditRepository;
import com.ledgerlab.ledger.persistence.JournalEntry;
import com.ledgerlab.ledger.persistence.JournalRepository;
import com.ledgerlab.ledger.persistence.TransferKey;
import com.ledgerlab.ledger.persistence.TransferRepository;
import com.ledgerlab.ledger.persistence.TransferRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private static final TransferResult POSTED = new TransferResult.Posted();
    private static final TransferResult ALREADY_PROCESSED = new TransferResult.AlreadyProcessed();

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final JournalRepository journal;
    private final DeferredCreditRepository deferredCredits;

    public LedgerService(
        AccountRepository accounts,
        TransferRepository transfers,
        JournalRepository journal,
        DeferredCreditRepository deferredCredits
    ) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.journal = journal;
        this.deferredCredits = deferredCredits;
    }

    /**
     * One batch is one transaction; results come in the order of the commands. A repeated delivery of the same
     * command is a no-op, also when two consumers process it at the same time.
     */
    @Transactional
    public List<TransferResult> applyBatch(List<TransferCommand> commands) {
        if (commands.isEmpty()) {
            return List.of();
        }
        // Everything the database would refuse must be rejected here: the consumer retries
        // database errors forever, which would stall the partition.
        var movable = commands.stream().filter(it -> it.amountCents() > 0 && it.fromAccount() != it.toAccount()).toList();
        var senders = new HashMap<AccountKey, Account>();
        accounts.lockAll(keys(movable, LedgerService::fromKey)).forEach(it -> senders.put(key(it), it));
        var receivers = new HashMap<AccountKey, Account>();
        accounts.findAll(keys(movable, LedgerService::toKey)).forEach(it -> receivers.put(key(it), it));
        var batch = new BatchAccounts(senders, receivers);
        // After the lock: a consumer posting the same transfer holds the same sender, so its commit is visible now.
        var seen = new HashSet<>(transfers.findRecorded(keys(commands, LedgerService::key)));

        var results = new ArrayList<TransferResult>(commands.size());
        for (var command : commands) {
            if (!seen.add(key(command))) {
                results.add(ALREADY_PROCESSED);
                continue;
            }
            var reason = rejectReasonFor(command, batch);
            if (reason == null) {
                post(command, batch);
                results.add(POSTED);
            } else {
                results.add(new TransferResult.Rejected(reason));
            }
        }
        var inserted = persist(commands, results, batch);
        // A rejection someone else recorded meanwhile is theirs to report, as with a duplicate found up front.
        for (var i = 0; i < commands.size(); i++) {
            if (results.get(i) instanceof TransferResult.Rejected && !inserted.contains(key(commands.get(i)))) {
                results.set(i, ALREADY_PROCESSED);
            }
        }
        return results;
    }

    private record BatchAccounts(Map<AccountKey, Account> senders, Map<AccountKey, Account> receivers, Map<AccountKey, Long> credits) {
        BatchAccounts(Map<AccountKey, Account> senders, Map<AccountKey, Account> receivers) {
            this(senders, receivers, new HashMap<>());
        }
    }

    private static @Nullable RejectReason rejectReasonFor(TransferCommand command, BatchAccounts batch) {
        if (command.amountCents() <= 0) {
            return RejectReason.INVALID_AMOUNT;
        }
        if (command.fromAccount() == command.toAccount()) {
            return RejectReason.SAME_ACCOUNT;
        }
        var from = batch.senders().get(fromKey(command));
        if (from == null) {
            return RejectReason.UNKNOWN_ACCOUNT;
        }
        var to = batch.receivers().get(toKey(command));
        if (to == null) {
            return RejectReason.UNKNOWN_ACCOUNT;
        }
        if (from.status() == AccountStatus.BLOCKED || to.status() == AccountStatus.BLOCKED) {
            return RejectReason.BLOCKED_ACCOUNT;
        }
        if (from.balanceCents() < command.amountCents()) {
            return RejectReason.INSUFFICIENT_FUNDS;
        }
        if (from.dailySpentCents() + command.amountCents() > from.dailyLimitCents()) {
            return RejectReason.DAILY_LIMIT_EXCEEDED;
        }
        return null;
    }

    private static void post(TransferCommand command, BatchAccounts batch) {
        batch.senders().computeIfPresent(fromKey(command), (key, from) -> from.withBalanceAndDailySpent(
            from.balanceCents() - command.amountCents(),
            from.dailySpentCents() + command.amountCents()));
        // Deferred also when the receiver sends in this batch: the applier adds it later, adding it here would count it twice.
        batch.credits().merge(toKey(command), command.amountCents(), Long::sum);
    }

    private Set<TransferKey> persist(List<TransferCommand> commands, List<TransferResult> results, BatchAccounts batch) {
        var decided = new ArrayList<TransferRow>();
        var posted = new ArrayList<TransferCommand>();
        for (var i = 0; i < commands.size(); i++) {
            var command = commands.get(i);
            switch (results.get(i)) {
                case TransferResult.AlreadyProcessed() -> {
                }
                case TransferResult.Rejected(var reason) -> decided.add(new TransferRow(command, reason));
                case TransferResult.Posted() -> {
                    decided.add(new TransferRow(command, null));
                    posted.add(command);
                }
            }
        }
        if (decided.isEmpty()) {
            return Set.of();
        }
        var inserted = transfers.insertAllIfAbsent(decided);
        // Posting it anyway would post it twice; the retry of the batch finds it recorded.
        for (var command : posted) {
            if (!inserted.contains(key(command))) {
                throw new ConcurrentPostingException(command);
            }
        }
        var entries = new ArrayList<JournalEntry>(posted.size() * 2);
        for (var it : posted) {
            entries.add(new JournalEntry(it.runId(), it.transferId(), it.fromAccount(), -it.amountCents()));
            entries.add(new JournalEntry(it.runId(), it.transferId(), it.toAccount(), it.amountCents()));
        }
        journal.insertAll(entries);
        accounts.updateBalances(keys(posted, LedgerService::fromKey).stream()
            .map(key -> Objects.requireNonNull(batch.senders().get(key), "sender of a posted transfer"))
            .toList());
        deferredCredits.insertAll(batch.credits());
        return inserted;
    }

    private static <K> Set<K> keys(List<TransferCommand> commands, Function<TransferCommand, K> key) {
        var keys = new LinkedHashSet<K>();
        commands.forEach(command -> keys.add(key.apply(command)));
        return keys;
    }

    private static AccountKey key(Account account) {
        return new AccountKey(account.runId(), account.accountNo());
    }

    private static TransferKey key(TransferCommand command) {
        return new TransferKey(command.runId(), command.transferId());
    }

    private static AccountKey fromKey(TransferCommand command) {
        return new AccountKey(command.runId(), command.fromAccount());
    }

    private static AccountKey toKey(TransferCommand command) {
        return new AccountKey(command.runId(), command.toAccount());
    }
}
