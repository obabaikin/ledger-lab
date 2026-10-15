# ADR 0014: The control page

Status: accepted · Date: 2026-10-16

## Context

A visitor with an invite starts a run and watches it go through the bank: the clients send, the
bank accepts, books and rejects, the ledger may be crashed, and the run ends with a verdict. The
page is served by control and knows the system only through control's API. The live numbers come
from three sources of different exactness (ADR 0015); only the books in the report are exact. A page
that shows a number its source does not give, or a relation that does not hold live, misleads the
reader the page is built for.

## Decision

**Polling, one request at a time.** The page asks control once a second: the session, its runs, then
the shown run, one after the other; a call due while the last one waits for its answer is skipped. It
stops asking once nothing is in progress or awaited. When control does not answer, one line says "connection
lost, retrying" and the last numbers stay. The page keeps its state in the browser only.

**Types from the specification.** The page's types are generated from `control/openapi.json`, which
a test checks against the running control. Control writes every field, a null one too, so every
field is required and a nullable one says so by its type. Error bodies are declared as JSON with
their `error` text; `Retry-After` and `Location` are declared where control sets them.

**Each number from its source.** Live, the clients' count is the generator's status, accepted,
booked and rejected are the reporting projection (a lower bound until the drain), and the instance
dots are Prometheus (`up` and the partitions held, a scrape or two behind). The page shows no
relation between the clients' count and the bank's live, as none holds while the projection trails
the gateway's answers. Money equalities, debits = credits and the total of all accounts unchanged,
are shown only from the sums of the books in the report.

**The display rule.** A part not read yet is shown as "—", never as 0. Accepted, booked, rejected
and in flight come from one snapshot: shown accepted is at least booked plus rejected, and in flight
is accepted minus booked minus rejected, never below 0. Counters move towards the last snapshot
without extrapolation and never count down. A second still running is not drawn; a second without
results breaks the latency line instead of drawing 0. Once the run is reconciled, the numbers are the
report's.

**Four phases.** Queued (queued or being set up), Clients sending, Bank finishing (draining, with a
mark once every accepted transfer is applied), Verdict (reconciled, failed or expired). A queued run
shows its place in line instead of the diagram; an expired one shows its reason.

**The page is always fresh.** `index.html` names its scripts by content hash, so a cached index keeps
an old page after a deploy. The jar gives every file the same 1980 timestamp, so a reload checked by
`Last-Modified` would be told 304. Static files are served with `Cache-Control: no-cache` and
without `Last-Modified`. The page shows the commit and the build time of its build.

**One run not final per session.** A start while a run of the session is queued, set up, running or
draining is refused with 409; a repeat of that run's `requestId` still gets that run. The check runs
under the queue's lock, so two tabs pressing Start at once make one run. The page holds Start back
with the reason on the button. A queued run cannot be cancelled: it leaves the queue by starting or
by expiring after `queue-timeout`, and the session waits for it.

**Buttons only when they act.** Stop is shown only while the clients send. The crash button is shown
when control says chaos is available and is held back with its reason on it: a countdown from
control's seconds, which may end the page's own count but never stretch it, or "while clients are
sending". A visitor without an invite sees the question, the diagram without numbers, the links and
how to get an invite.

## Considered and rejected

- **Server-sent events or WebSockets.** Every number already changes at most once a second and has
  to be read from three sources on control's tick; a stream adds a connection to keep without fresher
  numbers.
- **Cancelling a queued run.** It would need a state, a transition racing the scheduler's admission
  and a quota rule for a run that never started; a queued run waits at most `queue-timeout`.
- **Several runs of one session at once.** Two runs of one visitor would share the total TPS cap
  with each other and with other visitors, and the page would have two live runs to show.
- **Extrapolating counters between snapshots.** It would show numbers no source has given, and a
  crash would show bookings that did not happen.

## Consequences

- Live numbers are about a second behind control's tick, which is itself behind the bank.
- A visitor whose queued run was a mistake waits for it to start, finish or expire.
- After a restart of control, live numbers are "—" until its first tick; the mark that the books are
  final is in the database and survives the restart.
