# A run by hand

The steps control takes for a run, against the services' internal APIs, and how a client reads the
bank's answers. Start the services as in the [README](../../README.md#running-locally).

## The steps control takes

```bash
RUN=$(uuidgen)
# 1. open 100 accounts for the run (ledger, internal API)
curl -s -X POST localhost:8080/internal/runs/$RUN/accounts -H 'content-type: application/json' \
  -d '{"accountCount":100,"openingBalanceCents":1000000,"blockedAccounts":2,"dailyLimitCents":5000000}'
# 2. let the generator hit the gateway at 200 TPS for 10 seconds
curl -s -X POST localhost:8082/internal/simulations -H 'content-type: application/json' \
  -d "{\"runId\":\"$RUN\",\"tps\":200,\"durationSeconds\":10,\"accountCount\":100,\"blockedAccounts\":2}"
# 3. compare the four views
curl -s localhost:8082/internal/simulations/$RUN         # what the clients were told
curl -s "localhost:8082/internal/simulations/$RUN/transfers?outcome=UNKNOWN" # the clients' journal, by transfer
curl -s localhost:8081/internal/runs/$RUN/summary        # accepted, published, posted, rejected, latency
curl -s localhost:8080/internal/runs/$RUN/reconciliation # the ledger's own checks
# optional: stop early; the clients stop, the bank finishes what it accepted
curl -s -X POST localhost:8082/internal/simulations/$RUN/stop # 202 while stopping, 200 if already ended
# 4. once the generator has ended, close the period: nothing new of the run commits after this
curl -s -X POST localhost:8081/internal/runs/$RUN/close
curl -s "localhost:8081/v1/runs/$RUN/statement?limit=1000"   # the bank's statement, page by page via next
# 5. once the ledger has applied everything, match what the clients were told against the statement
curl -s localhost:8082/internal/simulations/$RUN/reconciliation # breaks by kind, unknowns settled
```

## The client-facing API

What the generator calls. It is published as OpenAPI:
[`gateway/openapi.json`](../../gateway/openapi.json) in the repository, `/v3/api-docs/bank` on a running gateway.
The generator knows the bank only through this spec ([ADR 0011](../adr/0011-contract-boundaries.md)).

```bash
curl -s -X POST 'localhost:8081/v1/transfers?wait=PT5S' -H 'content-type: application/json' \
  -d "{\"transferId\":\"$(uuidgen)\",\"runId\":\"$RUN\",\"fromAccount\":1,\"toAccount\":2,\"amountCents\":250}"
```

How a client reads the answer (full contract in [ADR 0006](../adr/0006-gateway-idempotency-outbox-and-waiting.md)):

| Response | Meaning | Client |
|---|---|---|
| 200 | accepted, final: `POSTED` or `REJECTED` | done |
| 202 | accepted, not final yet | asks again later (`Retry-After`) |
| 409 | not accepted: the run's period is closed | stops; the statement settles what is still unknown |
| other 4xx | not accepted | fixes the request, never retries it |
| 5xx or no answer | **unknown**: the transfer may be committed | retries with the same `transferId` |

A client without an answer reports "unknown", never "failed": "failed" invites a second payment
under a new key. The statement of a closed period settles every unknown: listed means accepted,
absent means never accepted ([ADR 0009](../adr/0009-closing-a-period-and-the-statement.md)).
Every page carries `entryCount`, the lines in the whole statement, so the client can prove it read
all of them.
The generator does this matching by transfer id; any discrepancy makes its report unbalanced and is
listed by id, up to 1000 per kind ([ADR 0010](../adr/0010-client-reconciliation.md)).
