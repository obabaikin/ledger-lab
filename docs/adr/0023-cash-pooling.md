# ADR 0023: Cash pooling as internal postings

Status: accepted · Date: 2026-11-25

## Context

The clients pay day and night, and like merchants 5 hot accounts receive about 30 % of all payments
([ADR 0021](0021-one-bank-around-the-clock.md)). Money that only flows one way piles up there: within days the other
accounts would run dry, and nearly every transfer would be rejected for insufficient funds. The bank needs its money
back where the load expects it, without breaking the books it reconciles every hour. Banks do this with cash pooling:
balances are levelled against one central account.

## Decision

**The bank's own account.** The ledger opens account 0, the pool, with €100 million beside the clients' accounts from 1
up, each opened with €10,000. The clients and the gateway do not know it.

**Two movements, both double entries.** Once a period the surplus of the 5 hot accounts above their opening balance
goes to the pool. Besides that a round over all accounts brings each back to its opening balance, its surplus to the
pool, its shortfall from it, spread evenly over 24 hours: one account every 0.86 s at 100,000 accounts, at most 100
of the round in one transaction, together with the hot accounts' sweep when it is due. Blocked accounts are left as they are. When the pool cannot cover a shortfall the account is
skipped and counted (`ledger.pool.skipped`). A round that is behind by more than a batch, after a stop, goes on from
now instead of catching up in a burst.

**One instance at a time, at one pace.** Every ledger instance ticks once a second; a transaction-level advisory lock
lets one work, and the round's position and times are read from the database, so two instances keep one pace, not
two. One statement locks the pool and every account of the batch in account order, the order every batch of transfers
locks in, so the pool never deadlocks with them. It holds both sides, so its credits are applied at once, not
deferred ([ADR 0018](0018-deferred-credits.md)).

**Internal, not a client's transfer.** A pool posting is a row in the ledger's `transfers` of kind `POOL`, with its two
journal entries, marked published when it is written: no result event, so neither the gateway, reporting nor the
clients' journal see it. The period check counts pool postings apart from the clients' ones, and every balance check
holds as for any posting: the sum of all accounts, the pool included, does not change.

## Considered

- **Pool transfers through the gateway.** They would be real postings too, but they would count as the clients'
  load and enter the gateway's statement, which the clients' journal must match transfer by transfer.
- **Resetting balances by `UPDATE`.** Money would appear and vanish without a journal entry, and the books would no
  longer reconcile.
- **Levelling every account every hour.** About 28 postings a second more for the whole bank, where the hot accounts
  hold almost all of the surplus.

## Consequences

- The load never runs out of money, and rejections stay those a bank would have: insufficient funds now and then,
  blocked accounts.
- The levelling is approximate: it races with live payments, which is fine, as the goal is a balance near the opening
  one once a day, not an exact one.
- The page shows a period's pool postings apart from the clients' numbers.
