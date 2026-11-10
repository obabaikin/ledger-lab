package com.ledgerlab.ledger.pool;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.AccountStatus;
import com.ledgerlab.ledger.domain.TransferKind;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.persistence.JournalEntry;
import com.ledgerlab.ledger.persistence.JournalRepository;
import com.ledgerlab.ledger.persistence.PoolRepository;
import com.ledgerlab.ledger.persistence.PoolState;
import com.ledgerlab.ledger.persistence.TransferRepository;
import com.ledgerlab.ledger.persistence.TransferRow;
import com.ledgerlab.ledger.service.AccountsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash pooling (ADR 0023): the money the clients' payments pile up on a few accounts goes back to where it came from,
 * through the bank's own account. Its postings are double entries like any other but are not transfers of a client:
 * no result event, no place in a statement.
 */
@Service
public class PoolService {

    public static final String VISITED_METRIC = "ledger.pool.visited";
    public static final String POSTINGS_METRIC = "ledger.pool.postings";
    public static final String SKIPPED_METRIC = "ledger.pool.skipped";

    private final PoolRepository pool;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final JournalRepository journal;
    private final PoolProperties props;
    private final AccountsProperties accountsProps;
    private final Counter visited;
    private final Counter postings;
    private final Counter skipped;

    public PoolService(
        PoolRepository pool,
        AccountRepository accounts,
        TransferRepository transfers,
        JournalRepository journal,
        PoolProperties props,
        AccountsProperties accountsProps,
        MeterRegistry registry
    ) {
        this.pool = pool;
        this.accounts = accounts;
        this.transfers = transfers;
        this.journal = journal;
        this.props = props;
        this.accountsProps = accountsProps;
        this.visited = Counter.builder(VISITED_METRIC).description("Accounts the pool looked at").register(registry);
        this.postings = Counter.builder(POSTINGS_METRIC).description("Postings between the pool and an account").register(registry);
        this.skipped = Counter.builder(SKIPPED_METRIC)
            .description("Accounts left below their opening balance because the pool could not cover them")
            .register(registry);
    }

    /**
     * Does what has come due by {@code now}: the next accounts of the round, and the hot accounts' surplus once per
     * interval. What is due is read from the database, so several instances together keep one pace, not one each.
     * Returns the number of accounts looked at; 0 also when another instance is at it.
     */
    @Transactional
    public int advance(Instant now) {
        if (!pool.tryLock()) {
            return 0;
        }
        var state = pool.state();
        var looked = 0;

        var perAccount = props.round().dividedBy(accountsProps.count());
        var due = Duration.between(state.roundAdvancedAt(), now).dividedBy(perAccount);
        if (due > 0) {
            var batch = (int) Math.min(due, props.maxBatch());
            var accountNos = new ArrayList<Integer>(batch);
            var next = state.nextAccountNo();
            for (var i = 0; i < batch; i++) {
                accountNos.add(next);
                next = next >= accountsProps.count() ? Account.FIRST_CLIENT_ACCOUNT_NO : next + 1;
            }
            level(accountNos, Levelling.TO_OPENING_BALANCE, now);
            looked += batch;
            // Behind by more than a batch, after a stop: the round goes on from now instead of catching up in a burst.
            var advancedAt = due > batch ? now : state.roundAdvancedAt().plus(perAccount.multipliedBy(batch));
            state = new PoolState(next, advancedAt, state.hotSweptAt());
        }

        if (!now.isBefore(state.hotSweptAt().plus(props.hotSweepInterval()))) {
            var hot = new ArrayList<Integer>();
            for (var no = Account.FIRST_CLIENT_ACCOUNT_NO; no < Account.FIRST_CLIENT_ACCOUNT_NO + props.hotAccounts(); no++) {
                hot.add(no);
            }
            level(hot, Levelling.SURPLUS_ONLY, now);
            looked += hot.size();
            state = new PoolState(state.nextAccountNo(), state.roundAdvancedAt(), now);
        }

        if (looked > 0) {
            pool.save(state);
            visited.increment(looked);
        }
        return looked;
    }

    private enum Levelling { TO_OPENING_BALANCE, SURPLUS_ONLY }

    private void level(List<Integer> accountNos, Levelling levelling, Instant now) {
        var numbers = new LinkedHashSet<>(accountNos);
        numbers.add(Account.POOL_ACCOUNT_NO);
        var locked = new LinkedHashMap<Integer, Account>();
        // The pool's account with the others in the one order every caller locks in, or the pool and a batch deadlock.
        accounts.lockAll(numbers).forEach(it -> locked.put(it.accountNo(), it));
        var poolAccount = Objects.requireNonNull(locked.get(Account.POOL_ACCOUNT_NO), "the pool's account");
        var postedAt = now.truncatedTo(ChronoUnit.MICROS);

        var moves = new ArrayList<TransferCommand>();
        var changed = new ArrayList<Account>();
        for (var accountNo : accountNos) {
            var account = locked.get(accountNo);
            if (account == null || account.status() != AccountStatus.ACTIVE) {
                continue;
            }
            var surplus = account.balanceCents() - account.openingBalanceCents();
            if (surplus > 0) {
                moves.add(new TransferCommand(UUID.randomUUID(), accountNo, Account.POOL_ACCOUNT_NO, surplus, postedAt));
            } else if (surplus < 0 && levelling == Levelling.TO_OPENING_BALANCE) {
                if (poolAccount.balanceCents() < -surplus) {
                    skipped.increment();
                    continue;
                }
                moves.add(new TransferCommand(UUID.randomUUID(), Account.POOL_ACCOUNT_NO, accountNo, -surplus, postedAt));
            } else {
                continue;
            }
            poolAccount = poolAccount.withBalance(poolAccount.balanceCents() + surplus);
            changed.add(account.withBalance(account.openingBalanceCents()));
        }
        if (moves.isEmpty()) {
            return;
        }
        changed.add(poolAccount);

        transfers.insertAllIfAbsent(moves.stream().map(it -> new TransferRow(it, null)).toList(), TransferKind.POOL);
        var entries = new ArrayList<JournalEntry>(moves.size() * 2);
        for (var it : moves) {
            entries.add(new JournalEntry(it.acceptedAt(), it.transferId(), it.fromAccount(), -it.amountCents()));
            entries.add(new JournalEntry(it.acceptedAt(), it.transferId(), it.toAccount(), it.amountCents()));
        }
        journal.insertAll(entries);
        // Both sides at once: the pool holds both locks, so its credit needs no deferring.
        accounts.updateBalances(changed);
        postings.increment(moves.size());
    }
}
