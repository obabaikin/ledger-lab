# Service level objectives

An SLI is a measured share of good events; an SLO is its objective over a rolling 30 days; the error budget is 1 − SLO,
the share the SLO leaves for failures.

The bank promises three things. The page shows the SLIs of the first two with their SLOs and error budgets; the third is
the verdict of every period.

| Objective | SLI (what is measured) | SLO (over a rolling 30 days) |
|---|---|---|
| Availability | share of minutes that are good: at least 99 % of the clients' requests got an answer | 99.9 % |
| Posted within 3 s | share of transfers from acceptance to result within 3 s | 95 % |
| Posted within 5 s | share of transfers from acceptance to result within 5 s | 99 % |
| Correctness | share of periods that reconcile `BALANCED` | 100 % |

The availability budget of 99.9 % over 30 days is `floor(43 200 × 0.001)` = 43 bad minutes. The budget of a time to
posting SLO is the transfers it allows over the threshold: 5 % of the judged transfers for 3 s, 1 % for 5 s. The
window starts on the hour 30 days back.

## Availability

SLI: share of measured minutes that are good. SLO: 99.9 %.

- The generator plays the bank's clients and counts their requests and answers by minute; control keeps the minutes.
- An answer is `2xx`, `4xx`, or `503 busy`, which tells the client to retry later. A server error, a `503` from an
  instance shutting down, a timeout and a failed connection are not.
- A minute without requests is not measured: it says nothing about the bank.

## Time to posting

SLI: share of judged transfers posted within 3 s, and within 5 s. SLO: 95 % within 3 s, 99 % within 5 s.

- The metric is the gateway's `gateway.transfer.latency`: from the gateway accepting a transfer to the gateway recording
  its result from the ledger, posted or rejected. Both of the gateway's replicas publish it as a histogram with a bucket
  at each threshold, 3 s and 5 s, so the transfers within a threshold are counted exactly and add up over the replicas.
- Over the judged transfers of 30 days: the share within 3 s is `within 3 s / judged`, the SLO 95 %; the share within
  5 s is `within 5 s / judged`, the SLO 99 %. Both share the same judged transfers. A transfer of exactly 3.0 s is
  within 3 s: the bucket `le="3.0"` includes its bound. Exactly 95.00 % meets the SLO.
- Control reads each minute's transfers and the two buckets from Prometheus 30 s after the minute ends and keeps
  trying for an hour; a minute keeps what it read first. A minute Prometheus did not answer for within the hour is not
  counted. Without a judged transfer in the window the page says "not measured", not 100 %.
- The counts start with the version that has the buckets at 3 s and 5 s: until 30 days have passed, the page says since
  when they are counted.
- Why shares of transfers, not minutes: a minute-based rule with a 1 % bound counted a minute bad for a few slow
  transfers among thousands, and its 43-minute budget said nothing about how many transfers waited. A share of
  transfers weighs every transfer once.
- Why a share within a threshold, not a p99: a summary's p99 is a moving window that cannot be read for exactly the past
  minute, nor added up over replicas. Three ways of reading it gave 5.5, 5.6 and 12.9 % bad minutes for the same six
  hours ([p99 analysis](../results/2026-11-14-p99-analysis.md)).
- The thresholds and SLOs are one setting of the chart, `slo.posting`, read by both the gateway and control.

## Correctness

SLI: share of periods that reconcile `BALANCED`. SLO: 100 %, no budget — one period that does not reconcile breaks it.

Every period's verdict is `BALANCED`: the ledger's books, the bank's events and the clients' journal agree. This holds
during crashes and loads above the limits too. It has no budget: one period that does not reconcile breaks it.

## What does not use the budget

Both objectives leave out the same minutes, by the rules below, but take out different things:

- **Availability**: only the bad minutes are taken out. A good minute during an event stays good and counted.
- **Time to posting**: every transfer of such a minute is taken out, the fast ones too. The page shows how many
  transfers were left out, planned and outside the bounds.

The minutes left out:

- **Announced maintenance.** A minute in a window declared at least 3 hours before its start is planned.
- **Outside the bounds.** A minute is outside the bounds if it meets
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
- A minute that is both in a maintenance window and outside the bounds counts as planned.
- Deploys and the bank's own failures are judged. A deploy is also checked on its own, per service and against a
  stricter rule: no transfer over 3 s, no `503` and no pool timeout while a service is replaced, or the service is
  rolled back ([ADR 0024](../adr/0024-deploys-without-a-maintenance-window.md)). That check leaves out the same crashes and
  loads, without the rule on restarts: a rollout restarts instances itself.

## Known limits

- A process that dies before Prometheus first scrapes it leaves no start time and is not counted. The pod is then down
  again, so its next start, or the bad minutes after the event, are still judged.
- A crash from the page during a load above the limit makes the load judged: its pods' starts are explained for the
  crash's window, not for the load's. Such minutes are judged: the error is on the strict side.
- During a deploy that adds a bucket, an old gateway replica publishes the histogram without it. Control counts a
  minute's transfers only on the replicas that publish both buckets, so the old replica's transfers are left out of
  both the judged transfers and those within the thresholds, rather than read as slow.
- A deploy of the ledger moves a stopping instance's partitions to the other one in about a second (measured on k3d):
  the instance leaves its Kafka group, and only its own partitions move ([ADR 0024](../adr/0024-deploys-without-a-maintenance-window.md)).
  A crashed instance does not leave the group: its partitions are given out again once Kafka's session timeout of 10 s
  notices it, so the transfers of those seconds can take over 3 s; a crash from the page is left out of the budgets.
- One node. In production a crashed pod would sit inside the objectives, with replicas across zones absorbing it. Here
  a crash stops the whole ledger for a while, so the crash button is left out of the budgets, and correctness under it
  is an objective of its own.

## Where it is in the code

- The gateway's histogram: `gateway/src/main/java/com/ledgerlab/gateway/service/TransferLatency.java`.
- Reading the minutes and the pods' starts from Prometheus:
  `control/src/main/java/com/ledgerlab/control/availability/PostingCollector.java`; the queries, the transfers counted
  only on replicas with both buckets: `control/src/main/java/com/ledgerlab/control/metrics/BankMetrics.java`.
- Judging the minutes and their transfers, both objectives in one query:
  `control/src/main/java/com/ledgerlab/control/availability/AvailabilityRepository.java`.
- The shares and the budgets used: `control/src/main/java/com/ledgerlab/control/availability/Availability.java`.
- The check of a deploy: `infra/helm/ledger-lab/files/release_check.py`; what tells it of a load above the promised
  rate: `control/src/main/java/com/ledgerlab/control/load/LoadAbovePromise.java`.
