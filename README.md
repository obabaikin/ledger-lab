# Ledger Lab

An event-driven double-entry ledger under load, with chaos testing and three-way reconciliation.

Pick a transaction rate, run it for up to half a minute, kill the ledger mid-run, and watch the books
still balance. Built to answer one question properly: *how do you prove no money was lost or
duplicated when consumers crash?*

**Live:** [ledger-lab.de](https://ledger-lab.de) opens with an invite link; invites on request via
[LinkedIn](https://www.linkedin.com/in/oleksii-babaikin).

> Status: work in progress. Ledger, gateway and generator are done; control takes a run from the
> queue through the bank to a stored verdict and shows it on its page. The whole system runs in a
> local Kubernetes cluster and on one server. The load investigation follows.

## What it does

- A **gateway** accepts transfers over a client-facing API with a client-supplied idempotency key,
  stores them in a transactional outbox and publishes them to Kafka. Retries never duplicate.
- The **ledger** applies each transfer as a double-entry posting in PostgreSQL. Delivery is
  at-least-once; processing is idempotent, so crashes and rebalances cannot double-post.
- A **generator** plays the bank's clients: it calls the gateway at 50, 100 or 300
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

## A run and its verdict

On the page, choose a rate and a duration and press Start. It shows the run in four phases (queued, clients
sending, bank finishing, verdict): the way a transfer takes from the clients through the gateway and
Kafka to the ledger with live counts and the state of each instance, transfers per second and the
time from the gateway to the ledger, and the crash button. Every live number comes from a named
source, and a number not read yet shows as "—" ([ADR 0014](docs/adr/0014-the-control-page.md)); they
are projected from the bank's own events ([ADR 0015](docs/adr/0015-live-numbers-from-the-banks-events.md)).

A run ends with a report: `BALANCED`, `BREAKS` or `INCOMPLETE` with the reasons, and only `BALANCED`
is green ([ADR 0013](docs/adr/0013-orchestrating-a-run-and-the-verdict.md)). It shows the sums of the
books: debits against credits and the total of all accounts before and after.

At most three runs hold a place at once, together at most 500 transactions per second, and only
once the ledger instances hold all partitions; the rest wait in a queue for up to five minutes ([ADR 0012](docs/adr/0012-invites-quota-and-the-run-queue.md)).

## Stack

Kotlin · Spring Boot 4 · Apache Kafka · PostgreSQL · Flyway · Testcontainers ·
Micrometer / Prometheus · Helm · k3s · cert-manager · Terraform (firewall, DNS); planned: Grafana

## Modules

| Module | Purpose | Status |
|---|---|---|
| `common` | The bank's internal Kafka messages, shared by the bank's services; no logic | done |
| `ledger-service` | Double-entry ledger, Kafka consumer, reconciliation, internal API | done |
| `gateway` | Client-facing transfer API, idempotency keys, transactional outbox, result status, optional wait | done |
| `generator` | Load simulator acting as the bank's clients (coroutines, retries with the same key) | done |
| `reporting` | The bank's numbers per run, projected from its Kafka events, internal API | done |
| `control` | Operations console: invites, the run queue, reconciliation, chaos button, UI | done |

## Running locally

Requirements: JDK 21, Docker.

```bash
docker compose up -d                      # Postgres (one schema per service), Kafka and its topics, Prometheus :9090
./gradlew test                            # integration tests with Testcontainers
./gradlew check                           # tests plus dead-code checks: detekt, knip (compiler warnings fail every build)
./gradlew :ledger-service:bootRun         # :8080
./gradlew :gateway:bootRun                # :8081, the bank's client-facing API
./gradlew :generator:bootRun              # :8082
./gradlew :reporting:bootRun              # :8084, the run's numbers from the bank's events
./gradlew :control:bootRun                # :8083, the page, invites, the run queue and orchestration (needs the other four)
```

A run through control, once an [invite](docs/guides/operations.md#invites) exists:

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

Local Kubernetes, the server, invites and work on the page: [docs/guides/operations.md](docs/guides/operations.md).

## Deployment

- **Kubernetes:** one Helm chart (`infra/helm/ledger-lab`) runs in k3d on a laptop, on one node the
  size of the server, and on the server's single-node k3s. The ledger runs three instances; a crashed
  pod restarts within a second. Every pod has a memory limit, and together they fit in 8 GB.
- **Server:** one VPS (6 vCPU, 12 GB), ordered by hand. Terraform manages only its firewall and the
  DNS record, so no configuration can buy a server.
- **Delivery:** every push to `main` builds the five images in CI and publishes them to GHCR;
  `deploy.sh <sha>` deploys a commit by hand and refuses while runs are in progress.
- **Security:** HTTPS from Let's Encrypt through cert-manager, requests limited per client address,
  a host firewall under the provider's, SSH with a key only, services without root or capabilities.
  Only the page is published.
- **Load:** the server offers 50 / 100 / 300 transactions per second, 100 by default, and at most
  500 for all runs together.

Details: [docs/guides/operations.md](docs/guides/operations.md#running-on-a-server).

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
- **One node, no failover.** The server is a single k3s node; if it goes down, so does the system.
- **No database backups.** Runs are disposable and deleted after 24 hours; invites are re-issued.
- **No push notifications.** Clients learn outcomes by retrying with the same key or asking for
  the status. Production would add signed webhooks with retried delivery.

## Design notes

Decisions with their trade-offs are recorded in [docs/adr](docs/adr).
