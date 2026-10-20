# ADR 0010: Client reconciliation

Status: accepted · Date: 2026-10-11

## Context

The bank reconciles its own books: the ledger checks its journal and balances, and the gateway's
counts are compared with the ledger's (ADR 0002). That says nothing about what clients were told.
A client can be told "accepted" for a transfer the bank lost, or "refused" for one the bank kept,
and every bank-side check still passes.

The generator is the client. Its journal holds every transfer it sent and what each answer said
(ADR 0007). After the period is closed, the gateway's statement lists everything the bank accepted
in it, and nothing new can appear (ADR 0009). Matching the two is what a merchant does with a
bank's settlement report.

## Decision

**The generator matches its journal against the statement by transfer id.**
`GET /internal/simulations/{runId}/reconciliation` pages through the statement and compares every
line with the journal entry under the same id. It reads the statement through its own model of the
gateway's published API, like any other call to the bank (ADR 0011). It only reads, so it can be
called again at any time; each call recomputes the report.

**Any discrepancy makes the report unbalanced, and each one is listed by id.** The aim is not that
discrepancies never happen, but that none is lost: each is either resolved or reported. Five kinds
count as breaks:

| Break | Meaning |
|---|---|
| `notInJournal` | the bank has a transfer the client never sent |
| `acknowledgedNotInStatement` | the client was told 2xx, the bank has no such transfer |
| `failedButInStatement` | the client was told 4xx, yet the bank accepted the transfer |
| `payloadMismatch` | amount or accounts differ |
| `statusMismatch` | the client was told a final status (`POSTED`, `REJECTED`) the bank does not confirm, or another reject reason |

A status the client knows only as not final (202), or could not read, is not compared: the bank has
moved on since, or the client has nothing to compare. The same holds for a statement line whose
status the client cannot read, as when the bank is newer than the client: it raises no
`statusMismatch`, and it keeps the report from being final (below), so it is not lost silently. A
reject reason is compared only when both sides could read it; with one side newer than the other,
one of them may not. A final status cannot have moved, because the gateway answers with a final
status only after storing it, so a `statusMismatch` is never a timing artefact and reconciling again
does not clear it.

**Unknown transfers are settled, not counted as breaks.** A transfer with no answer, including one
still being sent when a stop ended the run, is listed under `unknownResolved.accepted` if the
statement has it and `unknownResolved.notAccepted` if not. That is the statement doing its job.

**Each category lists at most 1000 ids; its count is exact.** A run where the gateway was down
could otherwise return tens of thousands of ids in one answer.

**Order of calls is the caller's job.** The control service (not built yet) is to call it once the
generator has ended, the period is closed and the ledger has drained. Before the generator ends the
answer is 409 `run still active`; before the close the gateway's 409 is passed on as `period not
closed`; for a run the generator does not know, or whose journal it has already released, 404. The
generator does not check the drain. After the close the verdict cannot change, since no row can be
added; until the drain ends only the status counts move, and `statementFinal` is false. The verdict
is final only when `statementFinal` is true; a statement line whose status the client cannot read
makes it false. The control service is to keep the verdict only from such a call.

Reconciling is a pure computation: it records nothing. A failure on any page drops the whole call,
and calling again starts from the first page, so there is nothing to undo.
A gateway that does not answer, or answers 5xx, on any page gives 503, and calling again is safe.
An answer the contract does not allow for gives 502, and so does a `next` the statement has
already given, which would otherwise page forever. Before it reports, the generator checks that it
read the whole statement (ADR 0009): a `transferId` listed twice, an `entryCount` that differs
between pages, or a line count other than `entryCount` after the last page gives 502. The generator
does not retry pages itself.

## Consequences

- With the control service, a run is green only if the bank's books balance and every client answer
  matches the bank.
- Matching counts alone is not enough: one lost and one extra transfer would cancel out. The line
  count only proves the statement was read in full; the matching is by id.
- A duplicate send uses the same transfer id, so it is one journal entry and one statement line.
- Control releases the journal once the verdict is written; otherwise it stays in memory for
  `generator.journal.retention` after the run, which bounds how late the reconciliation can be called.
- One client and one bank. With several banks, the client would record which bank got each
  transfer and match each bank's statement separately.
