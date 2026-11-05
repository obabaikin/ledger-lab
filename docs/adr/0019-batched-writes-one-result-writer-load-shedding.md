# ADR 0019: Batched writes, one result writer, load shedding

Status: accepted · Date: 2026-11-05

## Context

Every transfer cost several transactions: one to accept it in the gateway, one to post it in the
ledger, one to record its result in the gateway, and one more when a waiting request recorded the
result itself (ADR 0006). On the server's six cores the commits, not the work inside them, set the
limit. Batching one stage alone moved the queue to the next one,
so the stages change together.

Above its limit the gateway took every request on a virtual thread, and the open ones filled its
heap until it died: a heap dump showed about 120 KB per open submission.

## Decision

**The ledger posts a poll in one transaction.** One poll of Kafka, up to `max.poll.records` (100),
is one transaction: the senders are locked in one statement in key order, the transfers already
recorded are looked up after the lock (a consumer posting the same transfer holds the same sender),
and each transfer gets the outcome it would have had alone, debits of earlier transfers of the batch
included. Credits are deferred (ADR 0018). A failure retries the whole poll, and the idempotency key
`(run_id, transfer_id)` keeps the retry from posting twice (ADR 0003).

**The gateway records a poll of results in one update.** One `UPDATE … FROM unnest` per poll moves
each row to its final status, and never moves a row that is already final.

**Only the recorder writes results.** A request woken by a result no longer writes the row itself: a
second writer of the same row waited for the recorder's batch, and the batch for it. It reads the row
until the recorder's commit shows there, up to `gateway.wait-read.max-reads` times (5) with pauses
doubling from `gateway.wait-read.first-pause` (25 ms), and never past its wait. A row still not final
is answered 202, as for an expired wait.

**The gateway accepts submissions in batches.** Requests wait in one queue; each of
`gateway.accept.writers` (2) threads takes what gathered while its last batch committed, up to
`gateway.accept.batch-size` (200), so a lone request goes at once. A batch takes the shared period
locks of its runs in one order (ADR 0009), checks the closed periods after them and inserts all rows
in one statement in key order: two batches holding the same repeated keys in opposite orders each
waited for the other's uncommitted insert, and Postgres broke the deadlock by failing one batch.
Every request gets the answer it would have had alone: 202, a repeat, 409 or 422. Only a batch whose
data the database refused is written again one by one; a lost connection fails the batch as a whole,
because one by one would only multiply the failure. A writer survives any error of its batch, and a
request waits at most `gateway.accept.answer-timeout` (5 s) for its answer; past that it is a 5xx,
unknown to the client.

**Overload is shed at the edge.** At most `gateway.inflight.max-concurrent` (1500) submissions are
open at once, a wait for the result included; one more is answered `503 busy` with `Retry-After` at
once, before anything is written. Tomcat takes more connections than that (3000), so the refusal
comes from the gateway rather than from a connection that waits. The admission bound on inserts
(ADR 0006) stays inside it.

## Consequences

- One commit carries many transfers in each stage, so under load the commits stop setting the limit.
- A batch holds its senders' locks until it commits, so `max.poll.records` trades throughput against
  how long a sender waits.
- The client contract does not change: the same answers, now from a shared transaction. A request's
  answer waits for its batch's commit, at most `answer-timeout`.
- A woken request costs up to five reads instead of a write.
- The open-submission limit sizes the gateway's heap: 1500 open submissions take about 180 MB.
