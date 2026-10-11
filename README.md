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
| `common` | Kafka message contracts shared by producer and consumer | done |
| `ledger-service` | Double-entry ledger, Kafka consumer, reconciliation, internal API | done |
| `gateway` | Client-facing transfer API, idempotency keys, transactional outbox, result status, optional wait | done |
| `generator` | Load simulator acting as the bank's clients (coroutines, retries with the same key) | done |
| `control` | Operations console: runs, reconciliation, chaos button, UI | planned |

## Running locally

Requirements: JDK 21, Docker.

```bash
docker compose up -d                      # Postgres (one schema per service), Kafka and its topics
./gradlew test                            # integration tests with Testcontainers
./gradlew :ledger-service:bootRun         # :8080
./gradlew :gateway:bootRun                # :8081, the bank's client-facing API
./gradlew :generator:bootRun              # :8082
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
```

Client-facing API of the bank (what the generator calls):

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
absent means never accepted ([ADR 0009](docs/adr/0009-closing-a-period-and-the-statement.md)). Only the control console will be exposed; gateway, ledger and generator are
cluster-internal.

## Scope and non-goals

Nothing is silently dropped: every discrepancy is either resolved or reported.
What is deliberately left out, and what production would add:

- **Not a system of record.** A run is a disposable experiment with its own accounts, to be deleted
  after a day. Within a run durability is complete: a transfer is committed before it is
  acknowledged.
- **No reversals or compensating postings.** Outcomes are reported, not undone. Production would
  add refunds and authorise-then-capture, so money is only taken once the merchant confirms.
- **No push notifications.** Clients learn outcomes by retrying with the same key or asking for
  the status. Production would add signed webhooks with retried delivery.

## Design notes

Decisions with their trade-offs are recorded in [docs/adr](docs/adr).
