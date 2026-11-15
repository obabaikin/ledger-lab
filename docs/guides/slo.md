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
- **Unless the bank falls over.** If a service restarts in place during such an event, beyond what the event itself
  explains, every minute of the event is judged. A restart in place is a process of one of the project's services (the
  bank, the generator, control) starting again under the same pod: killed for its memory, crashed, or failing its
  liveness probe. A pod's first start is a new pod, not a restart.
  - A crash from the page stops the JVM of each ledger pod, and Kubernetes starts it again in the same pod. Control
    records which pods it crashed, so each of them may start once. A second start of a crashed pod, or a start of any
    other pod, makes the crash judged.
  - A load above the limit explains no restart: any restart makes it judged.
  - Starts count from the event's start to 60 s after its end, as a JVM comes up some tens of seconds after it went down.
  - Control has no access to Kubernetes. It reads each pod's `process_start_time_seconds` from Prometheus in windows of
    2 s, shorter than a JVM takes to start, and keeps every value once: the value is the start itself, so each start is
    counted once however the scrapes fall.
- Only bad minutes are taken out. A good minute during an event stays good and counted.
- A bad minute that is both in a maintenance window and outside the bounds counts as planned.
- Deploys and the bank's own failures are judged.

## Known limits

- A process that dies before Prometheus first scrapes it leaves no start time and is not counted. The pod is then down
  again, so its next start, or the bad minutes after the event, are still judged.
- A crash from the page during a load above the limit makes the load judged: its pods' starts are explained for the
  crash's window, not for the load's. Such minutes are judged: the error is on the strict side.
- During the first deploy of the gateway that publishes the histogram, an old replica still counts transfers without
  buckets, and its transfers read as slow. This happens once, for the minutes of that rollout.
- A deploy of the ledger pauses its consumers for about 30 s while Kafka moves their partitions. Until the ledger's
  group membership changes, each deploy can cost the time to posting one or two bad minutes.
- One node. In production a crashed pod would sit inside the objectives, with replicas across zones absorbing it. Here
  a crash stops the whole ledger for a while, so the crash button is left out of the budgets, and correctness under it
  is an objective of its own.

## Where it is in the code

- The gateway's histogram: `gateway/src/main/java/com/ledgerlab/gateway/service/TransferLatency.java`.
- Reading the minutes and the pods' starts from Prometheus:
  `control/src/main/java/com/ledgerlab/control/availability/FreshnessCollector.java`.
- Judging the minutes, both objectives in one query:
  `control/src/main/java/com/ledgerlab/control/availability/AvailabilityRepository.java`.
