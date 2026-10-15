# ADR 0002: Double-entry journal with independent reconciliation

Status: accepted · Date: 2026-10-07

## Context

A balance column alone cannot prove that no transfer was lost or applied twice. A test
that compares `SUM(balance)` before and after a run only shows that money was conserved,
not that each transfer was applied exactly once.

## Decision

Every posted transfer writes two journal rows (debit and credit) that sum to zero, in the
same transaction as the balance updates. Reconciliation then checks three views against
each other, inside one `REPEATABLE READ` snapshot:

1. total balance equals total opening balance;
2. the journal sums to zero;
3. every posted transfer has exactly the debit and credit its log entry implies (same
   accounts, same amount), and rejected transfers have no journal rows;
4. for every account, opening balance plus its journal movements equals its balance.

Check 3 is per transfer, not per run: a run-level count or sum would still pass when one
posting has three rows and another has one, or when a credit lands on the wrong account.

Rejected transfers are stored in the transfer log with a reason but write no journal rows.

## Consequences

- A lost transfer, a double posting, or a partial write each break at least one check.
- Journal rows double the write volume per transfer. That is the price of an audit trail.
- The checks are aggregate queries that can run while a run is still in progress. They stay
  cheap only while their plans do not depend on statistics of the new run; checks 3 and 4 are
  written for that (ADR 0016).
