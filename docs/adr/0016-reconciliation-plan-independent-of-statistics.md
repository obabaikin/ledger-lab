# ADR 0016: A reconciliation plan that does not depend on statistics

Status: accepted · Date: 2026-10-15

## Context

All runs share the same tables (ADR 0004), so a new run is a `run_id` that Postgres statistics
have not seen yet. The planner estimates such a run at one row. For check 3 of ADR 0002 (every
transfer's journal rows), written as `transfers LEFT JOIN journal_entries … GROUP BY … HAVING`,
that estimate picks a nested loop whose inner index scan matches on `run_id` only; `transfer_id`
becomes a filter. Every transfer then reads the whole run's journal.

Measured on a run of 10,605 transfers and 20,998 journal rows: the filter discarded 222,662,792
rows, 27 s of CPU (all buffer hits, no disk reads). With fresh statistics the same query takes
9.2 ms as a hash join. Statistics catch up only when autoanalyze passes the change threshold of
`transfers` and its once-a-minute round comes, so a 1000 TPS run waited about 40 s after it
stopped before control could reconcile it.

Control calls reconciliation with a 5 s timeout and retries on the next tick. A call it gave up
on kept running in the database, so up to 7 copies of the slow query ran at once.

## Decision

- Check 3 is a per-transfer lookup: `transfers CROSS JOIN LATERAL (aggregate over the journal
  rows of that transfer)`. The inner scan uses the existing index on `(run_id, transfer_id)`
  whatever the estimate; the run above takes 20.5 ms with or without statistics. The checks
  themselves do not change.
- Each reconciliation query gets `SET LOCAL statement_timeout` (4 s, below control's 5 s call
  timeout), so the database stops a query nobody waits for. `SET LOCAL` ends with the
  reconciliation's transaction and never reaches the pooled connection.
- A query cancelled at that timeout answers `503 {"error": "database statement timeout"}`;
  other database errors stay 500. Control already treats any non-2xx as "retry on the next tick".

## Alternatives

- **An index.** Already there; the planner did not use both of its columns.
- **`ANALYZE` before reconciliation.** About 45 ms plus 9 ms against 20 ms, writes statistics
  from a read path, and needs its own transaction outside the snapshot.
- **pg_hint_plan.** A hint breaks silently when a table or index is renamed, and the extension
  is not in `postgres:17-alpine`.
- **Caching the report.** It is an aggregate over a run that is still being written; any write
  invalidates it, and reconciliation must show the current state.

## Consequences

- With fresh statistics the lookup is a few milliseconds slower than the hash join
  (15.7 ms against 9.2 ms on the run above).
- The lookup is linear in the run's transfers. Runs today are at most about 30,000 transfers
  (1,000 TPS for 30 s); runs of hundreds of thousands would need this decision revisited.
- Five queries each just under 4 s (a database slow across the board) still exceed control's
  call timeout. That case is not covered on purpose.
