# ADR 0005: Service boundaries

Status: accepted · Date: 2026-10-07

## Context

The system should read like a small bank, not like a load-testing tool. Each part should
scale on its own axis, and the load generator should not be part of the bank.

## Decision

Three services form the bank, one sits outside it:

| Service | Owns | Scales by |
|---|---|---|
| `gateway` | Client-facing transfer API, idempotency at the edge, outbox to Kafka, result status | Replicas; the outbox publisher is safe to run concurrently |
| `ledger-service` | Double-entry postings, journal, reconciliation, result events | Replicas up to the number of partitions; beyond that PostgreSQL is the limit |
| `control` (planned) | Runs, sessions, invite tokens, UI, chaos actions via the Kubernetes API | One replica is enough |
| `generator` | Plays the bank's clients against the gateway's client-facing API | Replicas, each producing a share of the target rate |

One PostgreSQL instance hosts one schema and one role per service; a service can only see its
own schema. Services talk through Kafka (`transfers`, `transfer-results`) and, for operations
that are not part of the money flow, through internal HTTP endpoints under `/internal`.

## Consequences

- The ledger cannot be split into "accounts" and "journal" services: both are written in one
  transaction, and that transaction is the correctness guarantee. Sharding by account range is
  the next step if one PostgreSQL is not enough; it is not implemented.
- Reconciliation stays inside the ledger because it needs one snapshot of the ledger's tables.
- The generator knows nothing about Kafka or the database. If it did, the demo would not prove
  anything about the gateway.
- The build enforces this: the generator does not depend on `common`, and it knows the gateway
  only through its published spec, `gateway/openapi.json` (ADR 0011).
