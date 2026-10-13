# Ledger Lab

An event-driven double-entry ledger under load, with chaos testing and three-way reconciliation.

Pick a transaction rate, run it for up to a minute, kill the ledger mid-run, and watch the books
still balance. Built to answer one question properly: *how do you prove no money was lost or
duplicated when consumers crash?*

> Status: work in progress. Ledger, gateway and generator are done; control takes a run from the
> queue through the bank to a stored verdict. The whole system runs in a local Kubernetes cluster.
> The page, the deployment to a server and the load investigation follow.

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
- A **chaos button** deletes the ledger pod during a run. Reconciliation must still pass.

## Stack

Kotlin · Spring Boot 4 · Apache Kafka · PostgreSQL · Flyway · Testcontainers ·
Micrometer / Prometheus / Grafana · Helm · k3s · Terraform (Contabo)

## Modules

| Module | Purpose | Status |
|---|---|---|
| `common` | The bank's internal Kafka messages, shared by the bank's services; no logic | done |
| `ledger-service` | Double-entry ledger, Kafka consumer, reconciliation, internal API | done |
| `gateway` | Client-facing transfer API, idempotency keys, transactional outbox, result status, optional wait | done |
| `generator` | Load simulator acting as the bank's clients (coroutines, retries with the same key) | done |
| `reporting` | The bank's numbers per run, projected from its Kafka events, internal API | done |
| `control` | Operations console: invites, the run queue, reconciliation, chaos button, UI | in progress: entry by invite, quota, run queue, orchestration and the verdict; page and chaos button follow |

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

The same steps by hand, as control takes them:

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
listed by id, up to 1000 per kind ([ADR 0010](docs/adr/0010-client-reconciliation.md)). Only the control console is exposed; gateway, ledger and generator are
cluster-internal.

## Running in local Kubernetes

The same system on k3s in Docker ([k3d](https://k3d.io)), on one node the size of the target server
(8 GB). Requirements: JDK 21, Docker and, on `PATH`, k3d 5.9.0, kubectl 1.36.4 and Helm 4.3.0;
the cluster runs k3s 1.36.4.

```bash
infra/k3d/up.sh     # cluster, the five images, the chart (infra/helm/ledger-lab); safe to run again
infra/k3d/down.sh   # deletes the cluster with its volumes
kubectl -n ledger-lab exec postgres-0 -- psql -U admin -d ledgerlab -c \
  "INSERT INTO control.invite_tokens (token, expires_at, quota) VALUES ('<random token>', now() + interval '1 day', 5)"
kubectl -n ledger-lab port-forward svc/prometheus 9090   # Prometheus, inside the cluster otherwise
```

Control answers on `localhost:8083`; nothing else is exposed, and every service's actuator listens on
its own port inside the cluster. What the chart sets up:

- **The ledger runs three instances**, one consumer per partition each (six partitions), with Kafka's
  static membership: an instance that restarts under its name gets its own partitions back without a
  rebalance of the others; one that does not return keeps them until its 45-second session times out.
- **Readiness** is HTTP only, so a rebalance never takes the ledger out of its Service; whether runs may
  start is control's question, answered by the partitions the live instances hold.
- **Memory limits** on every pod, from measurements under load: a 1000 TPS run for 30 seconds took up
  to 5 cores on a fresh database, and the node stayed under 4.4 GB through runs back to back. JVMs take
  half their limit as heap; the rest is metaspace, thread stacks and buffers, 120 to 160 MB in every
  service.

| Pod | Memory limit |
|---|---|
| ledger ×3 | 512 MiB each |
| gateway | 768 MiB |
| generator | 576 MiB |
| control | 384 MiB |
| reporting | 448 MiB |
| Kafka (heap 512 MiB) | 768 MiB |
| PostgreSQL | 512 MiB |
| Prometheus | 512 MiB |

The services that consume Kafka wait for the topics in an init container with Kafka's tools, limited to
512 MiB; while it runs it reserves that much, so reporting's pod requests 512 MiB, not 448.

## Invites and the run queue

The page is opened with an invite link, `/?ref=<token>`. Invites are created by hand in the
database; there is no endpoint for them:

```sql
INSERT INTO control.invite_tokens (token, expires_at, quota)
VALUES ('<random token>', now() + interval '30 days', 5);  -- runs per hour
```

`POST /api/sessions {"token": ...}` opens a session and counts the visit; the session id is the
key to it, no cookie is set. `POST /api/sessions/{sessionId}/runs {"requestId", "tps",
"durationSeconds"}` answers 202 with the run `QUEUED` and its place; a repeat with the same
`requestId` and body returns the same run, a different body is 422. Over the invite's hourly quota,
or with the queue full, the answer is 429. At most three runs hold a place at once, together at most
1000 transactions per second; a run starts only once the ledger instances hold all partitions, and
a run that waits more than five minutes expires ([ADR 0012](docs/adr/0012-invites-quota-and-the-run-queue.md)).
The API is described in `control/openapi.json`, checked against the running service.

A run that leaves the queue gets 100 accounts in the ledger, its clients in the generator, a closed
period and a drained bank, then both reconciliations. Its report says `BALANCED`, `BREAKS` or
`INCOMPLETE` with the reasons; only `BALANCED` is green. Control never waits forever: a step that
cannot finish ends the run, and a restart of control resumes every run where it stood. The bank's
data of a run is deleted a day after it ended; control keeps the run and its report
([ADR 0013](docs/adr/0013-orchestrating-a-run-and-the-verdict.md)).

While a run is running or draining, its `GET` carries `live`: the bank's numbers from reporting,
which projects the bank's Kafka events into a row per transfer (accepted, posted, rejected, in
flight, the money posted, the bank's latency, a series per second), the clients' counts from the
generator, and from Prometheus the instances that are up, the partitions each ledger holds and the
redeliveries the ledger skipped. Live numbers trail the bank; the report keeps the final ones once
the projection counts what the books count, with the clients' exact latency
([ADR 0015](docs/adr/0015-live-numbers-from-the-banks-events.md)).

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
