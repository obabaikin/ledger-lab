# ADR 0021: One bank around the clock

Status: accepted · Date: 2026-11-15 · Supersedes [ADR 0004](0004-run-isolation.md), [ADR 0007](0007-stopping-a-run.md), [ADR 0012](0012-invites-quota-and-the-run-queue.md), [ADR 0013](0013-orchestrating-a-run-and-the-verdict.md) · Partly superseded by [ADR 0024](0024-deploys-without-a-maintenance-window.md): how a deploy reaches the server and what stops for it

## Context

Version 1 was a set of runs: a visitor chose a rate, the run got its own accounts, its clients sent for up to 30 s,
and control drained the bank and gave a verdict ([ADR 0013](0013-orchestrating-a-run-and-the-verdict.md)). Between
runs the bank stood idle, so nothing showed how it behaves over hours: through deploys, crashes at any moment,
partitions that age and are dropped, a database that grows. A bank does not stop between customers, and the questions
a production system raises — uptime, an error budget, reconciliation of what already happened while new money keeps
moving — need a bank that never stops.

## Decision

**One bank, one set of accounts, a load that follows the day.** The ledger opens 100,000 client accounts with
€10,000 each and a pool of €100 million at startup, once; the last 100 accounts are blocked, so rejections happen as in
a real bank. The generator plays the clients day and night: 200 transfers per second from midnight to 6:00 Berlin time,
then a random walk in steps of 100 or 200 every 5 minutes between 300 and 800, back at 200 by midnight. The walk is a
function of the time, so a restarted generator goes on with the same curve. A visitor with an invite can raise the
load for 10 to 30 s, to 900–2000 per second, above the day's highest base, one raise at a time for all visitors with at
least a minute between them, and can crash every ledger instance at most every 90 s.

**Hourly periods instead of runs.** A transfer belongs to the period in which the gateway accepted it, and every
service files it there by that time, carried in the message; a period is an hour. A minute after its end control closes it at the gateway, which fixes the period's entries: a later change to
them is a mismatch. Ten minutes after the end control reads the gateway's and the ledger's counts and waits until the
period is drained, with every accepted transfer posted or rejected on both sides. Then the ledger checks its books for
the period and the generator compares its journal of what the clients were told with the gateway's statement by
transfer id ([ADR 0009](0009-closing-a-period-and-the-statement.md), [ADR 0010](0010-client-reconciliation.md)).

**A verdict per period: `BALANCED` or `MISMATCH`.** `MISMATCH` names what disagrees: entries changed after the close,
the gateway's and the ledger's counts, the ledger's check, the sum of all balances, the clients' journal against the
statement, a statement not yet final. A period that gets no verdict within 40 minutes of its end is `FAILED` with the
reason (bank unavailable, drain stalled, a check that did not answer); it never shows as green. Reporting's numbers
from the bank's events are compared with the books as a note that does not change the verdict.

**The same tick as before.** The closing is a state machine stepped every second from a table, as runs were: a
restart of control resumes every period where it stopped, and its timers count from the stored times. Inside a
declared maintenance window the deadlines wait.

**Bounded data.** Transfers, journals and idempotency keys are kept 4 hours after their period ends, in partitions
that are dropped whole; Kafka keeps the same 4 hours. Period reports and hourly sums are kept. Once a day every
account is brought back to its opening balance, and the busiest accounts' surplus is swept to the pool every hour,
so the load never runs out of money.

**Service levels instead of a run's verdict.** Availability is measured per minute, with a 99.9 % objective over 30
days; the time to posting by transfer, 95 % within 3 s and 99 % within 5 s over 30 days, which replaced a first
per-minute rule. Each has an error budget on the page; crashes from the page, load above the promised rate and
declared maintenance windows are left out, with the rules in [the SLO guide](../guides/slo.md).

## Consequences

- The page shows one bank: its totals, the periods with their verdicts, the service levels and the charts. Nobody
  waits for a queue; there is no run to start or stop, and no per-invite quota.
- A verdict comes about 15 minutes after a period ends, not seconds after a run: the bank's money keeps moving while
  an hour is reconciled, so reconciliation reads the period's own rows, never the live balances alone.
- Deploys happen while clients send. The gateway is replaced without downtime
  ([ADR 0020](0020-gateway-deploys-without-downtime.md)); the other services stop briefly, and those minutes count
  against the budget unless a maintenance window was declared in time.
- Moving from version 1 needs a fresh install: the runs' schema and volumes are dropped, and `deploy.sh` refuses a
  database without the generator's role ([operations](../guides/operations.md#moving-from-runs-to-one-bank)).
- What the runs proved, crashes mid-flight with no money lost or doubled, is now proved every hour, on a bank that
  has been running for days.
