# Ledger Lab

An event-driven double-entry ledger under load, with chaos testing and three-way reconciliation.

Pick a transaction rate, run it for up to a minute, kill the ledger mid-run, and watch the books
still balance. Built to answer one question properly: *how do you prove no money was lost or
duplicated when consumers crash?*

> Status: work in progress. Ledger, gateway and generator are done; control takes a run from the
> queue through the bank to a stored verdict and shows it on its page. The whole system runs in a
> local Kubernetes cluster. The deployment to a server and the load investigation follow.

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
  journal and the transfer log. Any disagreement turns the run red, and so does a verdict that
  could not be completed.
- A **chaos button** crashes every ledger instance in the middle of a run, as `kill -9` would.
  Reconciliation must still pass.

## Stack

Kotlin · Spring Boot 4 · Apache Kafka · PostgreSQL · Flyway · Testcontainers ·
Micrometer / Prometheus · Helm · k3s; planned: Grafana, Terraform (Contabo)

## Modules

| Module | Purpose | Status |
|---|---|---|
| `common` | The bank's internal Kafka messages, shared by the bank's services; no logic | done |
| `ledger-service` | Double-entry ledger, Kafka consumer, reconciliation, internal API | done |
| `gateway` | Client-facing transfer API, idempotency keys, transactional outbox, result status, optional wait | done |
| `generator` | Load simulator acting as the bank's clients (coroutines, retries with the same key) | done |
| `reporting` | The bank's numbers per run, projected from its Kafka events, internal API | done |
| `control` | Operations console: invites, the run queue, reconciliation, chaos button, UI | in progress: entry by invite, quota, run queue, orchestration, the verdict and chaos; the page follows |

## Running locally

Requirements: JDK 21, Docker.

```bash
docker compose up -d                      # Postgres (one schema per service), Kafka and its topics, Prometheus :9090
./gradlew test                            # integration tests with Testcontainers
./gradlew :ledger-service:bootRun         # :8080
./gradlew :gateway:bootRun                # :8081, the bank's client-facing API
./gradlew :generator:bootRun              # :8082
./gradlew :reporting:bootRun              # :8084, the run's numbers from the bank's events
./gradlew :control:bootRun                # :8083, invites, the run queue and orchestration (needs the other four)
```

A run through control, once an invite exists (see below):

```bash
SESSION=$(curl -s -X POST localhost:8083/api/sessions -H 'content-type: application/json' \
  -d '{"token":"<invite token>"}' | jq -r .sessionId)
RUN=$(curl -s -X POST localhost:8083/api/sessions/$SESSION/runs -H 'content-type: application/json' \
  -d "{\"requestId\":\"$(uuidgen)\",\"tps\":100,\"durationSeconds\":10}" | jq -r .runId)
curl -s localhost:8083/api/sessions/$SESSION/runs/$RUN          # QUEUED, CREATED, RUNNING, DRAINING, RECONCILED
curl -s -X POST localhost:8083/api/sessions/$SESSION/runs/$RUN/stop # optional: stop the clients early
curl -s localhost:8083/api/sessions/$SESSION/runs/$RUN/report   # the verdict and what it rests on
```

Every step control takes, by hand against the services' internal APIs, and how a client reads the
gateway's answers: [docs/guides/run-by-hand.md](docs/guides/run-by-hand.md). Only control is meant to
be reached from outside; gateway, ledger, generator and reporting stay internal.

## Running in local Kubernetes

The same system on k3s in Docker ([k3d](https://k3d.io)), on one node the size of the target server
(8 GB). Requirements: JDK 21, Docker and, on `PATH`, k3d 5.9.0, kubectl 1.36.4 and Helm 4.3.0.

```bash
infra/k3d/up.sh     # cluster, the five images, the chart (infra/helm/ledger-lab); safe to run again
infra/k3d/down.sh   # deletes the cluster with its volumes
```

Control answers on `localhost:8083` (an invite is created as below); nothing else is exposed.

- **The ledger runs three instances** with Kafka's static membership: an instance that restarts under
  its name gets its own partitions back without a rebalance of the others.
- **Readiness** is HTTP only, so a rebalance never takes the ledger out of its Service; whether a run may
  start is control's question, answered by the partitions the live instances hold.
- **Every pod has a memory limit**, set from measurements under load; a JVM takes half of it as heap.
- **A crashed pod restarts within a second**: the cluster caps the kubelet's crash-loop backoff at 1 s
  (`infra/k3d/kubelet-crash-loop-backoff.yaml`), otherwise a second crash in a row waits 10 s, then twice as long.
  A cluster created before needs `down.sh` and `up.sh`.

## Invites and the run queue

The page is opened with an invite link, `/?ref=<token>`. Invites are created by hand in the
database; there is no endpoint for them:

```sql
INSERT INTO control.invite_tokens (token, expires_at, quota)
VALUES ('<random token>', now() + interval '30 days', 5);  -- runs per hour
```

At most three runs hold a place at once, together at most 1000 transactions per second, and only once
the ledger instances hold all partitions; the rest wait in a queue for up to five minutes
([ADR 0012](docs/adr/0012-invites-quota-and-the-run-queue.md)). A run ends with a report:
`BALANCED`, `BREAKS` or `INCOMPLETE` with the reasons, and only `BALANCED` is green
([ADR 0013](docs/adr/0013-orchestrating-a-run-and-the-verdict.md)). While it runs, it carries live
numbers projected from the bank's own events ([ADR 0015](docs/adr/0015-live-numbers-from-the-banks-events.md)).
The API is described in [`control/openapi.json`](control/openapi.json), checked against the running service.

## The page

Control serves the page at `/`; the invite link opens a session at `/s/<sessionId>`. It shows a run as
four phases (queued, clients sending, bank finishing, verdict), the way a transfer takes from the
clients through the gateway and Kafka to the ledger with live counts and the state of each instance,
transfers and the bank's latency per second, the crash button, and after the run the verdict with the
sums of the books: debits against credits and the total of all accounts before and after. A session
runs one run at a time. Every live number comes from a named source, and a number not read yet shows
as "—" ([ADR 0014](docs/adr/0014-the-control-page.md)).

The page is React and TypeScript in `control/page`; its types are generated from `control/openapi.json`.
Gradle downloads Node, runs the page's tests with control's and puts the built page into control's jar,
so `infra/k3d/up.sh` deploys it like the rest. For work on the page alone, with control on
`localhost:8083`:

```bash
cd control/page && npm ci && npm run types && npm run dev   # Vite on localhost:5173, /api proxied to control
```

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
