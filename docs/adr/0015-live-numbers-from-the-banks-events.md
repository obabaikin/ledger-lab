# ADR 0015: Live numbers from the bank's events, pods from Prometheus

Status: accepted · Date: 2026-10-13

## Context

The page shows a run while it happens: what the clients sent, what the bank accepted, booked and
rejected, the money moved, the bank's latency, and which instances are up. Up to three runs go side
by side; ledger and gateway run as several replicas; any instance may die. The final numbers must be
exact and must not depend on which instance died. The bank's databases do the bookings and must not
answer statistics every second.

## Decision

**A reporting service projects the bank's events.** `reporting` consumes the two topics the bank
already has, `transfers` (gateway's outbox) and `transfer-results` (ledger's outbox), in its own
consumer group and its own schema. Nothing is added to gateway or ledger. Each transfer is one row,
keyed by `transferId`; a command writes the amount and `acceptedAt`, a result writes the outcome,
the reason and `processedAt`, each as an upsert of its own columns. Events arrive at least once and
in any order, so a redelivery writes the same values again and a result may come before its command.
Offsets are committed after the database commit. Instances that write the command and the result of
the same transfer at once take row locks in `transferId` order.

**One snapshot per read.** `GET /internal/runs/{runId}/stats` reads totals, latency and a series in
one repeatable-read transaction: accepted, posted, rejected, in flight, the posted amount, rejections
by reason, bank latency p50/p99 (`processedAt − acceptedAt`, `percentile_cont`) over the run and per
second. The series counts increments per second by event time: acceptance by `acceptedAt`, or by
`processedAt` while the command is not seen yet, so the running "in flight" never goes below zero;
outcomes by `processedAt`. The whole series is recomputed on each read.

**Clients from the generator, pods from Prometheus.** The clients' counts come from the generator's
status; the clients' exact p50/p99 (every waiting first press of the run, interpolated as the bank's
SQL does) go to the report. Prometheus scrapes every service once a second for `up` and the
partitions each ledger instance holds. Redeliveries the ledger skips are in no event, so they come
from Prometheus too: `ledger_duplicates_total{run}`, summed over instances, the most seen kept; shown
as "at least N", not part of the verdict. Control reads all of it once a tick and keeps the last of
each part while its source cannot be read.

**Final means caught up with the books.** Once the books are final, control waits until the
projection counts what they count, up to 60 s after the books are final: commands seen = the
gateway's accepted, posted and rejected = the ledger's. Commands are counted apart from rows, as
every transfer has a result row once the drain is done; with every command seen, the amount and the
latency are whole too. 60 s is past Kafka's 45 s session timeout, so the partitions of a reporting
instance killed early in the drain have moved by then. The report keeps the stats, `projectionMatchesBooks`, and `notes` when reporting could not
be read or did not catch up. Notes never change the verdict; the verdict is the books'.

## Considered and rejected

- **Business numbers from Prometheus counters** (built, measured, replaced). A counter lives in one
  instance's memory: after `kill -9` of the gateway at 100 transfers/s and a restart, the series
  stayed 31 of 2892 accepted short for good, while the books were `BALANCED`. Every crash adds its
  own gap, and the exact counting, reset joining and waiting for scrapes built on top did not close it.
- **A statistics table written in the booking transaction.** A hot row in every booking and
  reporting load in the database that books.
- **`count(*)` in the bank's databases every second.** Loads the databases that do the bookings.
- **The projection inside control.** The events are the bank's internal contract; control talks to
  the bank only through its published APIs.
- **"Consumer lag is 0" as final.** It says reporting read what was published, not that every
  outbox row was published.
- **Checking the clients' counts against the projection.** The client reconciliation already
  compares every acknowledged transfer with the statement; live, the projection trails the gateway's
  answers, so no relation between them holds.

## Consequences

- Live numbers trail the bank by the outbox interval and Kafka: a lower bound until the drain.
- Bank latency subtracts the gateway's clock from the ledger's: right on one node or synchronised
  clocks, off by their difference otherwise.
- reporting is not a chaos target in phase 6; a killed instance delays the report by up to 60 s after
  the books are final, and one killed near the end of that leaves a note.
- The ledger's duplicate count loses what a killed instance counted since its last scrape; it is
  shown as a lower bound and nothing rests on it.
- Debits equal credits in the projection by construction (one amount per transfer); the live series
  shows the posted amount, the proof is the ledger's reconciliation.
- The run's rows are deleted with the rest of the bank's data a day after the run, by control.
