# Ledger Lab

An event-driven double-entry ledger under load, with chaos testing and three-way reconciliation.

Pick a transaction rate, run it for up to a minute, kill the ledger mid-run, and watch the books
still balance. Built to answer one question properly: *how do you prove no money was lost or
duplicated when consumers crash?*

> Status: work in progress. Ledger, gateway and generator are done and reconcile end to end;
> the control UI, Kubernetes deployment and the load investigation follow.

## What it does

- A **gateway** accepts transfers over a client-facing API with a client-supplied idempotency key,
  stores them in a transactional outbox and publishes them to Kafka. Retries never duplicate.
- The **ledger** applies each transfer as a double-entry posting in PostgreSQL. Delivery is
  at-least-once; processing is idempotent, so crashes and rebalances cannot double-post.
- A **generator** plays the bank's clients: it calls the gateway at 100 / 500 / 1000
  transactions per second and counts what was acknowledged.
- Transfers can be **rejected** for domain reasons: insufficient funds, blocked account,
  daily limit. Rejections are recorded and reconciled too.
- Every run gets its own **isolated set of accounts**, so several people can run experiments
  at the same time against the same cluster.
- **Reconciliation** cross-checks what the clients were told, what the gateway accepted,
  what Kafka carried and what the ledger posted, and inside the ledger the balances, the
  journal and the transfer log. Any disagreement turns the run red.
- A **chaos button** deletes the ledger pod during a run. Reconciliation must still pass.

## Stack

Kotlin · Spring Boot 4 · Apache Kafka · PostgreSQL · Flyway · Testcontainers ·
Micrometer / Prometheus / Grafana · Helm · k3s · Terraform (Contabo)

## Modules

| Module | Purpose | Status |
|---|---|---|
| `common` | The bank's internal Kafka messages, shared by ledger and gateway; no logic | done |
| `ledger-service` | Double-entry ledger, Kafka consumer, reconciliation, internal API | done |
| `gateway` | Client-facing transfer API, idempotency keys, transactional outbox, result status, optional wait | done |
| `generator` | Load simulator acting as the bank's clients (coroutines, retries with the same key) | done |
| `control` | Operations console: invites, the run queue, reconciliation, chaos button, UI | in progress: entry by invite, quota and run queue; its models of the services' internal APIs, checked against their specs |

## Running locally

Requirements: JDK 21, Docker.

```bash
docker compose up -d                      # Postgres (one schema per service), Kafka and its topics
./gradlew test                            # integration tests with Testcontainers
./gradlew :ledger-service:bootRun         # :8080
./gradlew :gateway:bootRun                # :8081, the bank's client-facing API
./gradlew :generator:bootRun              # :8082
./gradlew :control:bootRun                # :8083, invites and the run queue (needs the ledger to admit runs)
```

A run by hand, until the control service exists:

```bash
RUN=$(uuidgen)
# 1. open 100 accounts for the run (ledger, internal API)
curl -s -X POST localhost:8080/internal/runs/$RUN/accounts -H 'content-type: application/json' \
  -d '{"accountCount":100,"openingBalanceCents":1000000,"blockedAccounts":2,"dailyLimitCents":5000000}'
# 2. let the generator hit the gateway at 200 TPS for 10 seconds
curl -s -X POST localhost:8082/internal/simulations -H 'content-type: application/json' \
  -d "{\"runId\":\"$RUN\",\"tps\":200,\"durationSeconds\":10,\"accountCount\":100,\"blockedAccounts\":2}"
# 3. compare the four views
curl -s localhost:8082/internal/simulations/$RUN         # what the clients were told
curl -s "localhost:8082/internal/simulations/$RUN/transfers?outcome=UNKNOWN" # the clients' journal, by transfer
curl -s localhost:8081/internal/runs/$RUN/summary        # accepted, published, posted, rejected, latency
curl -s localhost:8080/internal/runs/$RUN/reconciliation # the ledger's own checks
# optional: stop early; the clients stop, the bank finishes what it accepted
curl -s -X POST localhost:8082/internal/simulations/$RUN/stop # 202 while stopping, 200 if already ended
# 4. once the generator has ended, close the period: nothing new of the run commits after this
curl -s -X POST localhost:8081/internal/runs/$RUN/close
curl -s "localhost:8081/v1/runs/$RUN/statement?limit=1000"   # the bank's statement, page by page via next
# 5. once the ledger has applied everything, match what the clients were told against the statement
curl -s localhost:8082/internal/simulations/$RUN/reconciliation # breaks by kind, unknowns settled
```

Client-facing API of the bank (what the generator calls). It is published as OpenAPI:
[`gateway/openapi.json`](gateway/openapi.json) in the repository, `/v3/api-docs/bank` on a running gateway.
The generator knows the bank only through this spec ([ADR 0011](docs/adr/0011-contract-boundaries.md)).

```bash
curl -s -X POST 'localhost:8081/v1/transfers?wait=PT5S' -H 'content-type: application/json' \
  -d "{\"transferId\":\"$(uuidgen)\",\"runId\":\"$RUN\",\"fromAccount\":1,\"toAccount\":2,\"amountCents\":250}"
```

How a client reads the answer (full contract in [ADR 0006](docs/adr/0006-gateway-idempotency-outbox-and-waiting.md)):

| Response | Meaning | Client |
|---|---|---|
| 200 | accepted, final: `POSTED` or `REJECTED` | done |
| 202 | accepted, not final yet | asks again later (`Retry-After`) |
| 409 | not accepted: the run's period is closed | stops; the statement settles what is still unknown |
| other 4xx | not accepted | fixes the request, never retries it |
| 5xx or no answer | **unknown**: the transfer may be committed | retries with the same `transferId` |

A client without an answer reports "unknown", never "failed": "failed" invites a second payment
under a new key. The statement of a closed period settles every unknown: listed means accepted,
absent means never accepted ([ADR 0009](docs/adr/0009-closing-a-period-and-the-statement.md)).
Every page carries `entryCount`, the lines in the whole statement, so the client can prove it read
all of them.
The generator does this matching by transfer id; any discrepancy makes its report unbalanced and is
listed by id, up to 1000 per kind ([ADR 0010](docs/adr/0010-client-reconciliation.md)). Only the control console will be exposed; gateway, ledger and generator are
cluster-internal.

## Invites and the run queue

The page is opened with an invite link, `/?ref=<token>`. Invites are created by hand in the
database; there is no endpoint for them:

```sql
INSERT INTO control.invite_tokens (token, expires_at, quota)
VALUES ('<random token>', now() + interval '30 days', 5);  -- runs per hour
```

`POST /api/sessions {"token": ...}` opens a session and counts the visit; the
session id is the key to it, no cookie is set. `POST /api/sessions/{sessionId}/runs {"requestId", "tps", "durationSeconds"}`
answers 202 with the run `QUEUED` and its place; a repeat with the same `requestId` returns the
same run. Over the invite's hourly quota, or with the queue full, the answer is 429. At most three
runs hold a place at once, and a run that waits more than five minutes expires
([ADR 0012](docs/adr/0012-invites-quota-and-the-run-queue.md)). The API is described in
`control/openapi.json`, checked against the running service.

## Scope and non-goals

Nothing is silently dropped: every discrepancy is either resolved or reported.
What is deliberately left out, and what production would add:

- **Not a system of record.** A run is a disposable experiment with its own accounts, to be deleted
  after a day. Within a run durability is complete: a transfer is committed before it is
  acknowledged.
- **No reversals or compensating postings.** Outcomes are reported, not undone. Production would
  add refunds and authorise-then-capture, so money is only taken once the merchant confirms.
- **No schema registry or contract broker.** Contracts are pinned by tests. A reader that meets a
  status or reason it does not know yet keeps what it can apply, and logs and counts the rest. Production would add a schema
  registry for the Kafka messages, a contract broker for clients and a dead-letter topic.
- **No push notifications.** Clients learn outcomes by retrying with the same key or asking for
  the status. Production would add signed webhooks with retried delivery.

## Design notes

Decisions with their trade-offs are recorded in [docs/adr](docs/adr).
