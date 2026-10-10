# ADR 0007: Stopping a run stops the clients, not the bank

Status: accepted · Date: 2026-10-09 · Updated: 2026-10-09

## Context

A run can end in two ways: its time is over, or someone stops it early. When the time is over,
the generator waits for every open request and all counts add up. An early stop used to cancel
everything at once: requests already sent never got their answer, were counted as failed, and
the "open requests" counter could stay above zero for good.

## Decision

**A stop is graceful.** The generator goes `RUNNING → STOPPING → STOPPED`. On stop it launches
no new transfers and makes no retries; a duplicate whose moment comes after the stop is not sent. Requests already sent keep running until they get their
answer, up to a grace period. `POST /internal/simulations/{id}/stop` answers 202 while the run
is stopping, and the caller polls the status until it is no longer active. If the run has already
ended, on its own or by an earlier stop, the answer is 200 with the final state, so a caller with
a stale view is never told to wait for a state that will not come. A stop is a `POST`, not a
`DELETE`, because the run stays readable afterwards; `DELETE` is kept for removing a run's data.

**The grace period is derived, not configured.** The slowest open request ends by its own HTTP
timeouts: connecting, then the requested wait plus the request timeout. The grace period is that
sum plus a second, so in practice nothing is left to cancel. No retry starts after a stop, so
backoffs do not count.

**Whatever is still open after the grace period is cancelled and counted as unknown.** A
cancelled request is not a failure. Each transfer of the client view ends in one of three
counts, as the client contract in ADR 0006 defines them: acknowledged (2xx), failed (4xx, or no
attempt reached the gateway) and unknown (sent, but no answer settled it). A 5xx or a timeout
on the last allowed attempt, including one cut short by a stop, is unknown, not failed.

**A transfer is judged by all its sends.** Besides retries, a share of transfers is sent a second
time with the same id and body, as a user pressing twice would. Any 2xx on any send acknowledges
the transfer; otherwise any send that may have arrived leaves it unknown; only then has it failed.
A duplicate is counted in `duplicatesSent`, never as a new transfer.

**The bank keeps working.** Only the client side stops. Every transfer the gateway accepted is
still published and applied, so the bank side of reconciliation (accepted = published =
applied) is complete after a stop as well.

## Consequences

- `requested = acknowledged + failed + unknown` once nothing is in flight, whether the run
  ran out of time or was stopped.
- The control service shows a stopped run as stopped, not failed: bank-side reconciliation is
  complete, the client side is complete if every answer arrived within the grace period and
  otherwise has an explicit "unknown" count. The caller waits for any final state with a
  timeout, never for `STOPPED` alone and never without a bound.
- Counter meanings are fixed: `requested` is counted before sending, `acknowledged` after the
  answer. Hence acknowledged ≤ accepted by the gateway ≤ acknowledged + unknown.
- A stop takes up to the grace period: 13 seconds with the defaults (2 s connect, 5 s wait,
  5 s request timeout, 1 s margin).
- A run that ends on its own also sends its pending duplicates, which adds at most the longest
  duplicate delay to its end.
- A run that ends on its own has no such bound: it waits for every open request, which ends
  by its own timeouts within three attempts (about 40 seconds with the defaults).
