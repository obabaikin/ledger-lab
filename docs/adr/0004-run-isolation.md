# ADR 0004: Logical isolation of runs

Status: accepted · Date: 2026-10-07

## Context

The demo is public. Two people may start a run at the same time. Without isolation, their
transfers would hit the same accounts and both reconciliations would fail for reasons that
have nothing to do with the ledger.

## Decision

Every run has a `run_id`. Accounts, transfers and journal rows carry it, and all primary
keys are composite with it. The Kafka partition key is `run_id:from_account`, so one run's
records cannot interleave with another's in a partition. Reconciliation is per run.

Isolation is logical: runs share the database, the topic, and the consumer. They compete
for CPU and I/O, which is visible in metrics and is the expected behaviour on a small node.

## Consequences

- Concurrent runs are independent in terms of correctness, not performance.
- Deleting a run cascades through all of its rows.
- The chaos test on a shared consumer is stronger, not weaker: after a pod kill, every
  active run must reconcile, not just one.
