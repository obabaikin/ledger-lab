# ADR 0003: Idempotent consumer that never skips a transfer

Status: accepted · Date: 2026-10-07

## Context

Kafka delivers at-least-once. After a consumer crash or rebalance, records between the
last committed offset and the crash are delivered again. The default Spring Kafka error
handler retries a failing record ten times and then skips it.

## Decision

- The primary key `(run_id, transfer_id)` on the transfer log is the idempotency guard.
  The transfer row is written with `INSERT ... ON CONFLICT DO NOTHING`; zero inserted rows
  means "already processed", also when two consumers race on the same transfer after a
  rebalance. The ledger service owns this contract, not the Kafka listener.
- Each transfer is one database transaction. Offsets are committed only after the batch
  has been processed, so a crash can cause redelivery but never loss.
- Processing errors are retried indefinitely with a short back-off. A ledger that stalls
  is acceptable; a ledger that silently drops a transfer is not. Only unparseable messages
  are logged and skipped, because retrying cannot fix them.

## Consequences

- Duplicate deliveries are visible as a metric (`ledger.transfers{outcome="duplicate"}`),
  which the chaos test uses as evidence that redelivery actually happened.
- A persistent failure (database down) blocks the partition until it is fixed. This is
  intentional and surfaces in consumer lag.
- No dead-letter topic yet; a poison message would need a producer-side fix.
- Because constraint violations are retried forever, the ledger must reject in code
  everything the database would refuse (for example a non-positive amount). A domain
  check that is missing here is not a 400, it is a stalled partition.
