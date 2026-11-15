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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

        var round = new ArrayList<Integer>();
        var perAccount = props.round().dividedBy(accountsProps.count());
        var due = Duration.between(state.roundAdvancedAt(), now).dividedBy(perAccount);
        if (due > 0) {
            var batch = (int) Math.min(due, props.maxBatch());
            var next = state.nextAccountNo();
            for (var i = 0; i < batch; i++) {
                round.add(next);
                next = next >= accountsProps.count() ? Account.FIRST_CLIENT_ACCOUNT_NO : next + 1;
            }
            // Behind by more than a batch, after a stop: the round goes on from now instead of catching up in a burst.
            var advancedAt = due > batch ? now : state.roundAdvancedAt().plus(perAccount.multipliedBy(batch));
            state = new PoolState(next, advancedAt, state.hotSweptAt());
        }

        var hot = new ArrayList<Integer>();
        if (!now.isBefore(state.hotSweptAt().plus(props.hotSweepInterval()))) {
            for (var no = Account.FIRST_CLIENT_ACCOUNT_NO; no < Account.FIRST_CLIENT_ACCOUNT_NO + props.hotAccounts(); no++) {
                hot.add(no);
            }
            state = new PoolState(state.nextAccountNo(), state.roundAdvancedAt(), now);
        }

        var looked = round.size() + hot.size();
        if (looked > 0) {
            level(round, hot, now);
            pool.save(state);
            visited.increment(looked);
        }
        return looked;
    }

    private enum Levelling { TO_OPENING_BALANCE, SURPLUS_ONLY }

    /**
     * Every account of both lists and the pool's own are locked by one statement, in the one order every caller locks
     * in: a second lock later in the transaction would take lower numbers after higher ones and deadlock with a batch.
     */
    private void level(List<Integer> round, List<Integer> hot, Instant now) {
        var numbers = new LinkedHashSet<Integer>();
        numbers.add(Account.POOL_ACCOUNT_NO);
        numbers.addAll(round);
        numbers.addAll(hot);
        var locked = new LinkedHashMap<Integer, Account>();
        accounts.lockAll(numbers).forEach(it -> locked.put(it.accountNo(), it));
        var postedAt = now.truncatedTo(ChronoUnit.MICROS);

        var moves = new ArrayList<TransferCommand>();
        var changed = new LinkedHashSet<Integer>();
        round.forEach(accountNo -> levelOne(accountNo, Levelling.TO_OPENING_BALANCE, locked, postedAt, moves, changed));
        hot.forEach(accountNo -> levelOne(accountNo, Levelling.SURPLUS_ONLY, locked, postedAt, moves, changed));
        if (moves.isEmpty()) {
            return;
        }
        changed.add(Account.POOL_ACCOUNT_NO);

        transfers.insertAllIfAbsent(moves.stream().map(it -> new TransferRow(it, null)).toList(), TransferKind.POOL);
        var entries = new ArrayList<JournalEntry>(moves.size() * 2);
        for (var it : moves) {
            entries.add(new JournalEntry(it.acceptedAt(), it.transferId(), it.fromAccount(), -it.amountCents()));
            entries.add(new JournalEntry(it.acceptedAt(), it.transferId(), it.toAccount(), it.amountCents()));
        }
        journal.insertAll(entries);
        // Both sides at once: the pool holds both locks, so its credit needs no deferring.
        accounts.updateBalances(changed.stream().map(no -> Objects.requireNonNull(locked.get(no), "a changed account is a locked one")).toList());
        postings.increment(moves.size());
    }

    private void levelOne(
        int accountNo,
        Levelling levelling,
        Map<Integer, Account> locked,
        Instant postedAt,
        List<TransferCommand> moves,
        Set<Integer> changed
    ) {
        var account = locked.get(accountNo);
        var poolAccount = Objects.requireNonNull(locked.get(Account.POOL_ACCOUNT_NO), "the pool's account");
        if (account == null || account.status() != AccountStatus.ACTIVE) {
            return;
        }
        var surplus = account.balanceCents() - account.openingBalanceCents();
        if (surplus > 0) {
            moves.add(new TransferCommand(UUID.randomUUID(), accountNo, Account.POOL_ACCOUNT_NO, surplus, postedAt));
        } else if (surplus < 0 && levelling == Levelling.TO_OPENING_BALANCE) {
            if (poolAccount.balanceCents() < -surplus) {
                skipped.increment();
                return;
            }
            moves.add(new TransferCommand(UUID.randomUUID(), Account.POOL_ACCOUNT_NO, accountNo, -surplus, postedAt));
        } else {
            return;
        }
        locked.put(Account.POOL_ACCOUNT_NO, poolAccount.withBalance(poolAccount.balanceCents() + surplus));
        locked.put(accountNo, account.withBalance(account.openingBalanceCents()));
        changed.add(accountNo);
    }
}
