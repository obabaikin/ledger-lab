# ADR 0013: Orchestrating a run and the verdict

Status: accepted · Date: 2026-10-12

## Context

A run admitted from the queue ([ADR 0012](0012-invites-quota-and-the-run-queue.md)) needs accounts
in the ledger, clients sending to the gateway, a closed period, a drained bank and two
reconciliations, in that order ([ADR 0009](0009-closing-a-period-and-the-statement.md),
[ADR 0010](0010-client-reconciliation.md)). Any of the three services can be slow, restart or be
gone while this happens, and so can control itself. Nobody may wait forever, and the result must
not look better than what was proven.

## Decision

**A tick, not a thread per run.** Every second control takes each run in `CREATED`, `RUNNING` or
`DRAINING` one step further. The state and its timestamps live in the `runs` table; a step moves a
run only from the state it read. Runs step side by side, so a slow call of one run does not use up
another run's deadline. A restart of control resumes every run from the table, and its timers count
from the times stored there. An answer control cannot read, for example from a newer service,
counts like no answer: it is retried within the phase's deadline.

**Setup (`CREATED`).** Control first asks the generator whether it already runs the clients of
this run; if it does, the run is `RUNNING` and nothing is started again (a repeated start would
replay the run). Otherwise it opens the run's accounts in the ledger (100 accounts of 10 000 €,
2 of them blocked, a daily limit of 50 000 €; all four are configuration), where 201 and 409 both
mean "opened", then starts the generator with the run's rate, duration and accounts. Control
records that it sent the start; if the generator later does not know a run whose start may have
reached it, it restarted, and the run goes on with its client lost instead of starting again. A 400 from
either fails the run with `setup rejected`; anything else is retried. A run that is not `RUNNING`
30 seconds after leaving the queue fails with `setup timed out`.

**Running.** `POST /api/sessions/{sessionId}/runs/{runId}/stop` answers 202 while the run is
`RUNNING` and passes the stop to the generator on the next tick; 409 `run not started` while the
run is queued or being set up; 200 in any later state, when there is nothing left to stop. When the generator reports `FINISHED` or `STOPPED`, or no longer
knows the run (it restarted: the client is lost), the run drains. Clients still active 60 seconds
after the run's duration are stopped and the run drains anyway.

**Draining.** The first step closes the period at the gateway; it is retried, and a period not
closed within the drain timeout fails the run with `period not closed`. The drain is over when the
gateway knows a result for every transfer it accepted and the ledger has applied the same number:
`gateway.posted + gateway.rejected = gateway.accepted = ledger.posted + ledger.rejected`. It gives
up when neither the ledger applies nor the gateway learns a result for 60 seconds, or 3 minutes
after the drain began. The
stall limit sits above the up to 45 seconds a killed ledger took, in our measurements, from the
kill to having its partitions back. If the bank does not answer until the 3 minutes are over, the run fails with
`bank unavailable`.

**The verdict.** Once the drain is over or has given up, control asks the generator for the client
reconciliation, once, and stores a report with the run:

- `BREAKS` if the ledger's books do not balance, if gateway and ledger count different posted or
  rejected transfers or reasons (compared only after a finished drain: before it, a difference is
  lag), if either of them counts more transfers than the gateway accepted (never lag, so the drain
  ends at once), or if the clients' journal and the statement disagree;
- otherwise `INCOMPLETE` with its reasons: `drain timed out: ledger applied X, gateway results Z,
  of Y`, `client lost`,
  `client reconciliation failed: ...` (the generator's 404, 409 or 502), `client reconciliation
  unavailable` (no answer by the drain deadline), `statement not final`;
- otherwise `BALANCED`. Only `BALANCED` is green.

A run that ends without a verdict is `FAILED` with its reason; a reconciled run is `RECONCILED`
with its result. A stopped run is reconciled like a finished one. `GET …/runs/{runId}/report`
returns the report, with the bank's p50 and p99 latency from acceptance to a known result, by the
gateway's clock, as a snapshot at the end of the run; while the run is not `RECONCILED` the answer is 409 `report not ready`, and a
failed run never has one. Rejections by reason in
the report are the ledger's: the clients know the reason only for the transfers they waited for.

**Cleanup.** Once an hour control deletes the ledger's and the gateway's data of runs that ended
24 hours ago or more (204 and 404 both count as deleted) and marks them purged. Control keeps the
run and its report; expired runs never had data and are left alone.

**Chaos.** `POST /api/sessions/{sessionId}/chaos` answers 501 `chaos not available`, and the
session says `chaosAvailable: false`, until deleting the ledger pod is built.

## Considered and rejected

- **A coroutine per run.** Lost on a restart of control, leaving runs that hold a place in the
  queue forever; recovering them needs the same steps the tick takes anyway.
- **Draining until `ledger applied = gateway accepted`.** The gateway may not have the results yet,
  so its statement would still show transfers in flight and the client reconciliation could not be
  final.
- **A fixed drain timeout only.** The drain lasts as long as the bank's backlog, not the run; a
  slow but healthy bank would be cut off. The stall limit follows progress instead.
- **Counting a drain timeout as a break.** A lost message and a slow bank look the same from the
  counts. Calling it a break would accuse a healthy bank; calling it green would hide a loss.
  Incomplete is neither.
- **Checks that add nothing.** `acknowledged ≤ accepted ≤ acknowledged + unknown` (the client
  reconciliation matches every transfer by id), `accepted = published` (follows from the drain),
  and the ledger's account count against the configuration (an empty ledger run cannot finish the
  drain against accepted transfers).
- **A CronJob publishing "run expired" to Kafka for cleanup.** Two owners of run data, both reached
  over HTTP; a topic and a consumer in every service would buy nothing here.

## Consequences

- A message lost between gateway and ledger shows as an incomplete run with the counts, not as a
  break with transfer ids; that would need the ledger's results listed by id.
- The generator is told how many accounts there are and how many are blocked, and relies on the
  ledger numbering them; a client of a real bank would get its accounts from the bank's API.
- The generator releases a journal 10 minutes after its run; many short runs in a row can fill its
  memory cap, and the next setup then times out.
- The clients' latency per run is not in the report yet: the generator's timers are shared by all
  runs, so with runs side by side their percentiles mix; it needs a histogram per run.
- The page shows progress live in a later step; until then the run's state and report are read
  through the API.
