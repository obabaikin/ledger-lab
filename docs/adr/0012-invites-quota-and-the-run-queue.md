# ADR 0012: Invites, quota and the run queue

Status: accepted · Date: 2026-10-12

## Context

Control is the only service reachable from the internet, and the cluster is a single node with the bank in 8 GB.
Anyone holding a link must be able to start a run, yet nobody may start enough runs to take the
bank down. The author also wants to know which invite links were opened, without tracking people.

## Decision

**Entry by invite.** An invite is a random token in the link (`/?ref=<token>`), created by hand in
the database with an expiry and a quota; there is no endpoint for it. `POST /api/sessions` with the
token opens a session. Unknown, inactive and expired invites all get the same 403. The session id
is the key to the session; it travels in the URL, not in a cookie, and a session sees only its own
runs (another session's run is 404, like a run that does not exist, and so is an id that is not
a UUID). Every error answer listed in the spec has the body `{"error": "..."}`.

**Visits.** Every opening of a known link, valid or not, adds one to `invite_visits` (first seen,
last seen, count). An unknown token leaves no row, so random tokens cannot fill the table. No IP
address, user agent or cookie is stored.

**Quota.** An invite may start `quota` runs in any hour, counted over all its sessions and in every
state, expired runs included: the quota limits how often "start" is pressed. Over the quota the
answer is 429 with `Retry-After`: the seconds until fewer than `quota` starts remain in the hour,
which stays right when the quota was lowered below the starts already made.

**Idempotent start.** The page sends a `requestId` with each start. A repeat with the same body in
the same session returns the same run and costs no quota; a different body is 422.

**The queue.** A start answers 202 at once with the run `QUEUED` and its place. At most
`max-queued` runs wait; one more is refused with 429 and no `Retry-After`, because no honest value
exists. A start holds a database connection while it waits for the queue's lock, so at most
`max-concurrent-starts` wait at once; one more is answered 503 with error `busy` and `Retry-After` after
`start-wait`, and nothing is started. At most `max-active` runs hold a place, and together they run at most `max-total-tps`
transactions per second (1000: one 1000 TPS run took up to 5 cores in our measurements, and three at
once ran the gateway and the generator out of memory). A scheduler hands free places to the oldest
queued runs in order, while their rates fit; a run that does not fit holds back the ones behind it, so
a large run never waits forever behind smaller ones. It admits nothing until the ledger instances hold
every partition of the transfers topic, as Prometheus sees them: a killed instance keeps its
partitions until it returns or its session times out, while the others still answer, and Prometheus
drops the series of an instance it cannot scrape. Prometheus not answering counts as not ready. A run
that waits longer than `queue-timeout` becomes `EXPIRED`. The queue lives in the `runs` table, so it survives a
restart of control, and waiting time is measured by the database clock from `created_at`.

Starts and scheduler ticks take one transaction-scoped advisory lock, so counts and inserts never
interleave: concurrent starts get exactly the free places of the queue and of the quota.

## Considered and rejected

- **A cookie for the session.** The project stores nothing in the visitor's browser by design, and
  a link that carries the session is easier to share inside the company that received it.
- **A queue in memory.** Lost on every restart of control; the table costs one index.
- **The ledger's readiness probe as the gate.** It said "ready" while any instance held a partition, so
  a run started next to a killed instance's stalled partitions; and as the Kubernetes readiness probe
  it took every instance out of the Service during a rebalance, though reconciliation reads need no
  Kafka.
- **Letting a smaller queued run overtake a larger one.** Fills the rate better, but a 1000 TPS run
  could wait forever behind a stream of small ones.
- **Only disabling the button against double clicks.** A retried request from the network would
  still start a second run.
- **Hashing the tokens.** A leaked database gives away the quota of a demo, nothing more; the
  mapping of tokens to recipients is kept outside the repository.

## Consequences

- A run admitted from the queue is `CREATED`; orchestration takes it from there
  ([ADR 0013](0013-orchestrating-a-run-and-the-verdict.md)).
- An email scanner that fetches the link without running the page's script does not count as a
  visit; one that runs it does.
