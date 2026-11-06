# ADR 0018: Deferred credits

Status: accepted · Date: 2026-11-05

## Context

A posting locked both accounts of a transfer until it committed. When the ledger began to post a
whole poll of transfers in one transaction (ADR 0019), a batch held the locks of every account it
touched, senders and receivers alike. Receivers are where batches meet: many senders pay the same
account, and their transfers sit in different partitions, so batches of different consumers waited
for each other on the same receivers, and the more transfers a batch held, the longer.

Three variants were measured on the server: locking receivers as before, deferring credits only to
accounts marked as busy receivers, and deferring every credit. Only deferring every credit kept the
ledger up with the gateway; marking accounts did worse than no deferral at all.

## Decision

**A batch locks only its senders.** The debit, the check of funds and limits, both journal rows and
the transfer log are written in the batch's transaction, as before. The credit goes into
`deferred_credits`, one row per receiver and batch, its amounts summed.

**One applier adds the credits to the balances.** Every ledger instance runs it on a schedule
(`ledger.deferred-credits.apply-interval`, 250 ms), but only the one that gets a transaction-level
advisory lock works; the others skip the round. Two appliers would lock the same receivers and wait
for each other. In one transaction it takes up to `ledger.deferred-credits.batch-size` rows
(`DELETE … RETURNING`, oldest first, `SKIP LOCKED`), locks their accounts in the order a batch locks
its senders, adds the sums and commits; a full batch is followed by the next at once. A crash rolls
the transaction back and the rows stay for the next applier.

**Applied rows are deleted, not marked.** Measured on the server's Postgres at the target rate,
deleting kept the applier's transaction at half the time of marking (p99 4.8 ms against 10.6 ms), the
WAL at 52 MB against 89 MB over two minutes, and the table at its pending rows only. Marked rows
would also need a cleanup, because a bank that runs around the clock never deletes the run that
holds them.

**Reconciliation counts pending credits as balance.** The journal holds a credit before the balance
does, so every check that compares balances (ADR 0002) reads `balance_cents` plus the account's rows
in `deferred_credits`. Deleting a run deletes its pending credits first, then its accounts: the
order in which the applier takes them, so a deletion right after a run waits for the applier
instead of deadlocking with it.

## Semantics

**A transfer is complete when it is posted.** Its journal rows, the sender's debit and its entry in the
transfer log commit in one transaction; from then on it is `POSTED` and nothing undoes it. The credit
reaching the receiver's balance is not part of that step; the journal already holds it.

**What stays:** the posting is atomic, double-entry and idempotent, and no transfer spends money that
is not there; reconciliation holds at any moment; clients see transfer statuses and statements as
before, and no API returns an account's current balance.

**What changes:** a sender's funds are checked against its available balance, without credits not yet
applied. A receiver that sends right after it was paid may be rejected for insufficient funds although
the money is booked; the check can refuse money that is there, never allow money that is not. Here
that is acceptable: the clients are simulated, a rejection is a reconciled outcome, and the delay is a
fraction of a second.

**When it stops being right:**

- the product promises that received money can be spent at once, as instant payments do;
- an API shows balances: it must show booked and available apart;
- the delay grows towards seconds because the one applier falls behind. `ledger.credit.delay` shows it
  first; an alert on its p99 (for instance above 2 s) is not set up yet.

## Consequences

- Money reaches a receiver's balance shortly after the transfer posted: p50 0.15 s, p99 0.61–0.65 s
  at 1000–1600 transfers per second on the server (`ledger.credit.delay`).
- A sender is locked once per batch; receivers are not locked by batches at all, and the applier
  holds each of them for one short transaction per round.
- One applier is a single point of throughput. It is far from its limit at the measured rates
  (about four rounds a second); beyond that the work splits by account range, each range with its
  own lock.
