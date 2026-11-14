# Service level objectives

The bank promises three things. The page shows the first two with their error budgets; the third is the verdict of
every period.

| Objective | A minute is good if | Target |
|---|---|---|
| Availability | at least 99 % of the clients' requests got an answer | 99.9 % of minutes over a rolling 30 days |
| Time to posting | at most 1 % of its transfers took longer than 2 s from acceptance to result | 99.9 % of minutes over a rolling 30 days |
| Correctness | — | every period `BALANCED` |

A budget of 99.9 % over 30 days is `floor(43 200 × 0.001)` = 43 bad minutes.

## Availability

- The generator plays the bank's clients and counts their requests and answers by minute; control keeps the minutes.
- An answer is `2xx`, `4xx`, or `503 busy`, which tells the client to retry later. A server error, a `503` from an
  instance shutting down, a timeout and a failed connection are not.
- A minute without requests is not measured: it says nothing about the bank.

## Time to posting

- The metric is the gateway's `gateway.transfer.latency`: from the gateway accepting a transfer to the gateway recording
  its result from the ledger, posted or rejected. Both of the gateway's replicas publish it as a histogram with a bucket
  at the threshold, so the slow transfers of a minute are counted exactly and add up over the replicas.
- A minute is bad if more than 1 % of its transfers were slower than 2 s. Exactly 1 % is good, and a transfer of exactly
  2.0 s is fast: the bucket `le="2.0"` includes its bound.
- Control reads each minute from Prometheus 30 s after it ends and keeps trying for an hour. A minute without transfers,
  or one Prometheus did not answer for within the hour, is not measured.
- Why a share over a threshold, not a p99: a summary's p99 is a moving window that cannot be read for exactly the past
  minute, nor added up over replicas. Three ways of reading it gave 5.5, 5.6 and 12.9 % bad minutes for the same six
  hours ([p99 analysis](../results/2026-11-14-p99-analysis.md)).
- The threshold is one setting of the chart, `slo.freshnessThreshold`, read by both the gateway and control.

## Correctness

Every period's verdict is `BALANCED`: the ledger's books, the bank's events and the clients' journal agree. This holds
during crashes and loads above the limits too. It has no budget: one period that does not reconcile breaks it.

## What does not use the budget

The same rules apply to both budgets.

- **Announced maintenance.** A bad minute in a window declared at least 3 hours before its start is planned.
- **Outside the bounds.** A bad minute is outside the bounds if it meets
  - a crash set off from the page, from the crash to 60 s after it, or
  - a load raised above 1300 transfers per second, from its start to 30 s after its end.

  Loads up to 1300 per second are promised and judged. Above that, visitors test the bank past its limits: refusals and
  slow transfers may come, but the books must still balance.
- **Unless the bank falls over.** If a service restarts in place during such an event, every minute of the event is
  judged. A restart in place is a process of one of the project's services (the bank, the generator, control) starting
  again under the same pod: killed for its memory, crashed, or failing its liveness probe. Control sees it in Prometheus
  as a changed `process_start_time_seconds` of the same pod; it has no access to Kubernetes. A crash from the page
  deletes pods, and their replacements are new pods, not restarts in place.
- Only bad minutes are taken out. A good minute during an event stays good and counted.
- A bad minute that is both in a maintenance window and outside the bounds counts as planned.
- Deploys and the bank's own failures are judged.

## Known limits

- The restart check looks back 5 minutes, to bridge the restart's gap without samples. It can mark a minute up to four
  minutes after a restart, and so cancel the exclusion of an event that starts soon after. Such a minute is judged: the
  error is on the strict side.
- A deploy of the ledger pauses its consumers for about 30 s while Kafka moves their partitions. Until the ledger's
  group membership changes, each deploy can cost the time to posting one or two bad minutes.
- One node. In production a crashed pod would sit inside the objectives, with replicas across zones absorbing it. Here
  a crash stops the whole ledger for a while, so the crash button is left out of the budgets, and correctness under it
  is an objective of its own.

## Where it is in the code

- The gateway's histogram: `gateway/src/main/java/com/ledgerlab/gateway/service/TransferLatency.java`.
- Reading the minutes from Prometheus: `control/src/main/java/com/ledgerlab/control/availability/FreshnessCollector.java`.
- Judging the minutes, both objectives in one query:
  `control/src/main/java/com/ledgerlab/control/availability/AvailabilityRepository.java`.
