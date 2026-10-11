# ADR 0011: Contract boundaries: the bank's internal messages and its published API

Status: accepted · Date: 2026-10-11

## Context

Statuses and reject reasons cross two boundaries: ledger → gateway over Kafka, inside the bank,
and gateway → clients over HTTP. A first attempt put every wire type, the gateway's HTTP bodies
included, in `common` and let the generator use it. That had two flaws:

- The generator compiled against the bank's classes. A change to the bank changed the client
  with it, so the client could never notice that the bank had changed.
- A strict reader turns a value it does not know yet into a lost message. The gateway read the
  ledger's result event strictly: a reject reason from a newer ledger failed to parse, the event
  was skipped as unreadable, and the transfer stayed `PUBLISHED` for good.

## Decision

**Inside the bank, one shared contract.** `common` holds the messages between ledger and gateway
and nothing else: `TransferCommand`, `TransferResultEvent`, `TransferOutcome`, `RejectReason`. No
HTTP types, no logic. Both services are released by one team, so sharing the classes is safe.
`WireContractTest` writes the field names and enum values out as literals: renaming one in the
code fails the test instead of silently changing the wire.

**Towards clients, a published API.** The gateway owns its HTTP types and publishes them as
OpenAPI (`/v3/api-docs/bank`, internal endpoints excluded). A copy is kept in `gateway/openapi.json`,
and a test fails when the running gateway serves anything else. Every API change therefore shows
up in review as a diff of the spec.

**The client knows the bank only through that spec.** The generator does not depend on `common`.
It has its own model of the API (package `bank`). `BankContractTest` checks that model against
`gateway/openapi.json`: the path and query parameter it calls exist, every field the generator
sends or reads is in the spec, every required field is sent, and its statuses and reasons are
exactly the spec's. This is a light consumer-side contract test, without a contract broker.

**Control reads the services' internal HTTP APIs the same way.** The ledger, the generator and
the gateway's internal endpoints publish their own specs (`ledger-service/openapi.json`,
`generator/openapi.json`, `gateway/openapi-internal.json`, the gateway's split into the groups
`bank` and `internal`), each checked against the running app. Control has its own model of the
calls it makes, and `ServiceContractsTest` checks it against those files: paths and their
parameters, field names, required request fields and enum values. Status codes and nullable
fields are not checked yet. Only the Kafka messages are shared classes.

**Every entry that reads an enum from the other side tolerates a value it does not know, where the
message can still be applied, and says so.** An outcome it does not know cannot be applied.

| Entry | Value it does not know | Result | Visible as |
|---|---|---|---|
| gateway reads a result event | reject reason | the outcome applies; the reason is stored as received; the API shows `null` until the gateway knows the value | warning, `gateway.results.unknown-reason` |
| gateway reads a result event | outcome | cannot be applied: the event is unreadable and skipped, like any unparseable message (ADR 0003) | error log |
| generator reads an answer | status | acknowledged without a status; the status code decides (ADR 0006) | warning, `generator.gateway.unreadable` |
| generator reads an answer | reject reason | the status is kept, the reason is `null` | warning, `generator.gateway.unreadable` |

The gateway stores the reason as text rather than as `null`. Its schema requires a reason on
every `REJECTED` row, so a `null` would fail the write. A failed write is retried, so one such
event would stop its partition. The text also loses nothing: once the gateway knows the new
value, the stored reason reads as known.

A service reads its own database strictly (the ledger's outbox, for example): only that service
writes it. A new value is therefore released in two steps: first a version that can read it, then
one that writes it. Rolling back below a version that can read the stored data is not allowed; a
strict read makes such a rollback fail loudly instead of quietly dropping the value.

## Consequences

- A new reject reason can be released by the ledger before the gateway, and by the gateway
  before the generator. A new outcome needs the gateway first.
- Tolerance never hides a contract change: the contract tests fail on a renamed field or value,
  and at runtime every value that was not understood is logged; the tolerated ones are also counted.
- Production would add a schema registry with compatibility rules for the Kafka messages, a
  contract broker for client tests, and a dead-letter topic for messages no version can read.
  None of these is here.
