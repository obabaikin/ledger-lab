# ADR 0001: JDBC instead of JPA for the ledger

Status: accepted · Date: 2026-10-07

## Context

The ledger has four tables and a handful of statements, most of them row locks and
arithmetic updates on hot rows. Correctness depends on the exact SQL that runs and the
order in which rows are locked.

## Decision

Use Spring's `JdbcClient` with hand-written SQL. No entity classes, no ORM session.

## Consequences

- Lock order, `FOR UPDATE`, and `UPDATE ... SET balance = balance - :amount` are explicit
  and visible in code review.
- No lazy loading, dirty checking or flush ordering to reason about under contention.
- More boilerplate for mapping rows; acceptable for four tables.
- Schema evolution goes through Flyway migrations, as it would with JPA.
