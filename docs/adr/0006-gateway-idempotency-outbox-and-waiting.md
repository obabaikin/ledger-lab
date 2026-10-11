# ADR 0006: Gateway: idempotency key at the edge, table as outbox, optional waiting

Status: accepted · Date: 2026-10-07 · Updated: 2026-10-09

## Context

Clients retry. A retry after a timeout must not create a second transfer. Clients also expect
an answer in one request, while the ledger is asynchronous behind Kafka.

## Decision

**Idempotency key at the edge.** The client supplies `transferId`. The gateway inserts with
`ON CONFLICT DO NOTHING` on that key; a repeated submission returns the current state of the
existing transfer, and the same key with a different payload is refused with 422. This is the
first line; the ledger's `(run_id, transfer_id)` key is the second.

**The transfer row is the outbox.** Accepting a transfer commits one row and answers 202
before Kafka is involved. A publisher claims rows with `published_at IS NULL` using
`FOR UPDATE SKIP LOCKED`, sends them, and marks them in the same transaction. Several
gateway replicas can publish concurrently; a crash between send and mark re-sends, which the
ledger absorbs. A separate outbox table was not needed: one event type, one row per request,
and the row's lifecycle (`ACCEPTED → PUBLISHED → POSTED | REJECTED`) is the status clients ask for.

**Results come back over Kafka.** The ledger publishes `transfer-results` through its own
outbox. Two listeners in the gateway consume them: a shared consumer group records the result
in the database, and a per-instance group wakes any open request waiting for that transfer.
Marking a row published never downgrades a status that already became final.

**The database first, then the answer.** A guarantee comes from one atomic commit, and in each
service that is its own database transaction. Kafka and the database commit separately, so
nothing that spans both is guaranteed (the dual-write problem). The instance that wakes a
waiting request therefore writes the result itself, with the same idempotent update the shared
listener uses, and answers from the committed row. A client that saw a final status never
sees an older one afterwards; if the answer is lost, a retry with the same key reads it from
the database.

**The wake-up listener is an anonymous group.** Each instance has its own group with a random
id, so it receives every result. It never commits offsets (no acknowledgments and no commit of
the initial position on assignment), so a restart starts from the latest result and the broker
removes the empty group instead of keeping it for the offset retention period.

**Waiting is a convenience, not the main path.** `POST /v1/transfers?wait=PT5S` keeps the request open until the result
arrives or the wait expires, then answers 200 with the outcome or 202 with the current state.
Requests run on virtual threads, so thousands of open waits cost little. The core stays
asynchronous; this mirrors how instant-payment front ends sit on asynchronous ledgers.
The wait is capped by the gateway (`gateway.max-wait`); expiry is a 202, not an error.

**The client contract is explicit.** Every client reads a response the same way:

| Response | The transfer is | The client |
|---|---|---|
| 200 | accepted and final: `POSTED`, or `REJECTED` with `rejectReason` | is done |
| 202 | accepted, not final yet: `ACCEPTED` or `PUBLISHED`; `Retry-After` hints when to ask | learns the result later |
| 400, 404 | not accepted: invalid request, unknown or missing API version | fixes the request; a retry gets the same answer |
| 422 | not accepted: the `transferId` was already used with another payload | has a bug; a key is never reused |
| 409 | not accepted: the run's period is closed (ADR 0009) | stops; the statement settles what is still unknown |
| 5xx | unknown: it may already be committed | retries with the same `transferId` and payload |
| no answer (timeout, connection lost after sending) | unknown | same as 5xx |
| connection not established | not received | retries |

- A transfer is identified by its key, not by its content: two equal payments under two keys
  are two payments. A client creates one key per intent to pay and keeps it before calling.
- A retry with the same key and payload is always safe, and it settles "unknown": it either
  accepts the transfer or returns the existing one. `GET` answering 404 does not settle it,
  because the original request may still commit afterwards. The statement of a closed period
  does (ADR 0009): after the close nothing new commits.
- Once any attempt for a key ended unknown, the key stays unknown until an answer arrives,
  even if later attempts cannot connect.
- A client that gives up without an answer reports "unknown", never "failed". "Failed" invites
  a new payment under a new key, which is how money gets sent twice.
- Once a transfer is committed, the gateway answers 2xx only. If the result cannot be read
  while a client waits, the answer is the same 202 as for an expired wait; the failure goes to
  a metric and the log for operators. 5xx still means "unknown" in the contract: the gateway
  itself cannot tell whether a commit whose acknowledgement was lost went through, and a proxy
  or a crashed process can produce one after the commit.

## Consequences

- Four independent counts become available for reconciliation: acknowledged by the client,
  accepted and published by the gateway, applied by the ledger.
- End-to-end latency (`accepted_at` to `resulted_at`) is measured per transfer, with both
  timestamps taken from the database clock, because JVM and database clocks can drift apart.
- A waited request costs one extra idempotent update; for the shared listener it then
  changes nothing.
- The wake-up listener may miss results published while an instance was down. That only means
  a waiting client gets 202 on timeout and finds the result via `GET /v1/transfers/{id}`.
- Every instance parses every result even when nobody waits. Cheap at the target load; to be
  measured before optimising.
- The load generator follows the contract: each transfer ends acknowledged, failed or
  unknown, after at most three attempts with exponential backoff and jitter. Hence
  acknowledged ≤ accepted by the gateway ≤ acknowledged + unknown.
- The client-facing API is versioned in the first path segment using Spring's built-in API
  versioning; internal endpoints are not, since their only consumer ships in the same release.
