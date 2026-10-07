# Ledger Lab

An event-driven double-entry ledger under load, with chaos testing and three-way reconciliation.

Pick a transaction rate, run it for up to a minute, kill the ledger mid-run, and watch the books
still balance. Built to answer one question properly: *how do you prove no money was lost or
duplicated when consumers crash?*

> Status: work in progress. Phase 1 (ledger core) is done; generator, control UI, Kubernetes
> deployment and the load investigation follow.

## What it does

- A **gateway** accepts transfers over a public API with a client-supplied idempotency key,
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
Micrometer / Prometheus / Grafana · Helm · k3s · Terraform (Hetzner)

## Modules

| Module | Purpose | Status |
|---|---|---|
| `common` | Kafka message contracts shared by producer and consumer | done |
| `ledger-service` | Double-entry ledger, Kafka consumer, reconciliation, internal API | done |
| `gateway` | Public transfer API, idempotency keys, transactional outbox | planned |
| `generator` | Load simulator acting as the bank's clients | planned |
| `control` | Operations console: runs, reconciliation, chaos button, UI | planned |

## Running locally

Requirements: JDK 21, Docker.

```bash
./gradlew :ledger-service:bootRun   # starts Postgres and Kafka from compose.yaml automatically
./gradlew test                      # integration tests with Testcontainers
```

Internal API (used by the control service, not meant to be public):

```bash
RUN=$(uuidgen)
curl -s -X POST localhost:8080/internal/runs/$RUN/accounts \
  -H 'content-type: application/json' \
  -d '{"accountCount":100,"openingBalanceCents":1000000,"blockedAccounts":2,"dailyLimitCents":5000000}'
curl -s localhost:8080/internal/runs/$RUN/reconciliation
```

## Design notes

Decisions with their trade-offs are recorded in [docs/adr](docs/adr).
