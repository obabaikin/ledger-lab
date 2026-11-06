# From about 200 to about 1600 transfers per second (2026-11-06)

What limited the bank on one node, how it was found, what changed, what was tried and rejected, and the October
version and the current one measured side by side on the same server. The October limit is in the
[server baseline](2026-10-23-server-baseline.md); the decisions are in
[ADR 0018](../adr/0018-deferred-credits.md) and [ADR 0019](../adr/0019-batched-writes-one-result-writer-load-shedding.md).

## Result

| | October version (`1082fb7`) | Current version (`453a540`) |
|---|---|---|
| Steps of 30 s without a refusal | below 300 TPS | up to 1600 TPS (two staircases of three) |
| Client p99 at 800 TPS | 69–93 s | 0.35–0.66 s |
| Accepted, not yet booked, at the end of 800 TPS | 25,030–42,931 | 127–162 |
| Where it breaks | 300–400 TPS | 2000 TPS: everything taken, but the bank falls behind |
| Reconciliation | every run `BALANCED` | every run `BALANCED` |

The changes moved the limit, not the guarantees: every run ended `BALANCED`, refusals and crashes included.

## Method

- Both versions were deployed in turn on the node of the October baseline: 6 vCPU, 12 GB, k3s, the whole bank and
  the load generator on it.
- A **staircase**: one generator run per step, 30 s each, back to back, the rate rising, after a warm-up of three
  runs of 300 transfers per second for 30 s; the generator called directly, 100 accounts per run. Three staircases
  per version and range, every run reconciled afterwards.
- This is not the October method (two runs of 120 s per rate): the numbers compare the two versions with each
  other. The share of each change was not measured apart; the numbers quoted for single changes below come from
  their probe branches.

| | October version | Current version |
|---|---|---|
| Ledger | 3 × 512 MiB | 3 × 750 MiB |
| Gateway | 1068 MiB, half as heap, Serial collector, 10 connections, at most 100 inserting at once | 1568 MiB, two thirds as heap, G1, 40 connections, at most 1000 inserting at once (200 in the chart, raised for these measurements), at most 1500 open |
| PostgreSQL | 912 MiB (768 MiB in October) | 912 MiB |

## 200 to 800 transfers per second

`503 busy` answers per step, three staircases each (run 1 / run 2 / run 3):

| TPS | October version | Current version |
|---|---|---|
| 200 | 25 / 0 / 4 | 0 / 0 / 0 |
| 300 | 47 / 5,651 / 0 | 0 / 0 / 0 |
| 400 | 310 / 9,823 / 5,711 | 0 / 0 / 0 |
| 500 | 5,864 / 9,712 / 8,412 | 0 / 0 / 0 |
| 600 | 9,723 / 10,860 / 11,274 | 0 / 0 / 0 |
| 700 | 11,009 / 9,712 / 11,258 | 0 / 0 / 0 |
| 800 | 10,533 / 10,790 / 10,769 | 0 / 0 / 0 |

Client p99 per step, from the first attempt to the final answer:

| TPS | October version | Current version |
|---|---|---|
| 200 | 30 s / 0.32 s / 2.3 s | 1.0 s / 0.22 s / 0.45 s |
| 300 | 169 s / 179 s / 75 s | 0.33 s / 0.37 s / 0.24 s |
| 400 | 166 s / 172 s / 162 s | 0.35 s / 0.24 s / 0.32 s |
| 500 | 148 s / 149 s / 157 s | 0.28 s / 0.42 s / 0.24 s |
| 600 | 131 s / 122 s / 136 s | 0.59 s / 0.30 s / 0.80 s |
| 700 | 104 s / 96 s / 115 s | 0.51 s / 0.52 s / 0.31 s |
| 800 | 81 s / 69 s / 93 s | 0.66 s / 0.35 s / 0.45 s |

From 600 on the October version took 391–494 transfers per second of 600–800 offered, and clients gave up on up
to 7,263 transfers per step; the current version took every step at the offered rate.

## 1000 to 1600 transfers per second (current version)

| Insert admission | 1000 TPS | 1200 TPS | 1400 TPS | 1600 TPS |
|---|---|---|---|---|
| 200: `503` per run | 0 / 107 / 4 | 0 / 0 / 28 | 5,859 / 5,558 / 4,600 | 7,447 / 8,502 / 7,118 |
| 200: taken per second | 1000 / 996 / 1000 | 1200 / 1200 / 1200 | 1223 / 1397 / 1326 | 1466 / 1351 / 1458 |
| 1000: `503` per run | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / about 1,300 |
| 1000: client p99, runs 1 and 2 | 1.3 s / 0.68 s | 0.57 s / 0.78 s | 1.3 s / 0.84 s | 1.4 s / 1.8 s |

The third staircase with 1000 lost its table to a dropped port forward; its refusals, read from Prometheus, came in
one 15 s window at 1600. At 2000, one run of 30 s took 58,408 transfers, `BALANCED`, but the gateway-to-ledger p99
rose from 0.4 s to 8.2 s during the run.

## How the bottlenecks were found

Every change started from an estimate and a measurement that named the next bottleneck, not from a list of tuning
tips:

1. **Estimate the limits first.** Before a fix was built, its limit was worked out from measured costs and the
   expected effect written down.
   - *Commits against the disk.* On the server's idle disk one `fdatasync` takes about 554 µs (`pg_test_fsync`), so
     the write-ahead log is flushed at most about 1,800 times a second, one flush at a time. At three commits per
     transfer (accepted, posted, result recorded) that caps the bank near 600 transfers a second however fast the
     code; it passed 400–500. The fix had to cut commits per transfer: batching.
   - *Parallelism of the ledger.* Six partitions give six transactions at a time, each waiting for the commit of the
     one before in its partition: the ceiling is six divided by the time of one transaction, so larger or shorter
     transactions help, more consumers do not.
   - The gateway's limit of open requests and the connection pools were sized the same way, below.

   Not every expectation held, and the measurement decided: load shedding was expected to cap the gateway at about
   1400 transfers a second, and the bank took about 1600; deferring only the busiest receivers' credits was expected
   to be enough, and it was worse than deferring none.
2. **Where the time goes.** Each staircase splits a transfer's latency by stage: the gateway's outbox, Kafka and the
   ledger, the ledger's outbox, the result back to the gateway. The stage that grows first under load is the next
   to look at.
3. **What it waits for.** A sampler reads PostgreSQL's `pg_stat_activity` twice a second and counts what each
   service's sessions wait for: CPU, the write-ahead log at commit, row locks. Who holds a lock is read with
   `pg_blocking_pids`, not guessed from the code.
4. **What uses the CPU and the memory.** CPU per process on the node (`pidstat`), CPU profiles of the gateway (Java
   Flight Recorder), heap dumps, and the services' metrics: collector pauses, connection pool, open requests.
5. **Hypotheses, built and tried apart.** Each fix went into a probe branch of its own and was measured on the same
   staircase and server, alone and together with the others. A fix that moved the bottleneck was kept, and the next
   measurement named the next one; the kept fixes were then written again, cleanly, in one step into the main line.

## What limited the bank, and what changed

Each fix moved the queue to the next stage, so the stages changed together.

- **Every transfer was several commits**: accepting it, posting it, recording its result, and once more when a
  waiting request recorded the result itself. Now one poll of Kafka is one transaction in the ledger, and one poll
  of results one update in the gateway ([ADR 0019](../adr/0019-batched-writes-one-result-writer-load-shedding.md)).
  In the probe the ledger's share of waits on the write-ahead log fell from 47 % to 6–10 %. Batching the ledger
  alone made things worse, the queue moving into the gateway's result recording, so both went in together.
- **Batches waited for each other on the receivers.** A batch locked every account it touched, and many senders pay
  the same receivers. Now a batch locks only its senders; credits wait in a table until one applier adds them to the
  balances, and reconciliation counts them meanwhile ([ADR 0018](../adr/0018-deferred-credits.md)). The price:
  money reaches a receiver's balance after p50 0.15 s and p99 0.65 s, and in that window the receiver cannot spend
  it. This changes what a balance means, not what a transfer means; the ADR says what stays, what changes and when
  it stops being right.
- **Two writers of the same result.** A request woken by its result wrote the row, as the result recorder did; once
  the recorder wrote batches, each waited for the other. Now only the recorder writes and the request reads its
  row. Row-lock waits in the gateway fell from 29–40 % to 1–18 % of its waits in the probe.
- **The gateway accepted one request per transaction.** Now requests wait in a queue and two writers commit what
  gathered while their last batch committed, in one insert, each request with the answer it would have had alone.
  Two batches with the same repeated keys in opposite orders deadlocked; the keys now go in one order.
- **The gateway's garbage collector.** Below 1792 MB the JVM picks the Serial collector, which stops the whole
  service for each collection: up to 0.39 s at 900 TPS. With G1 and a larger heap, GC time fell from 30 % to 12 %
  and pauses from 0.57 s to 0.16 s; the two were changed together.
- **The gateway ran out of memory under overload.** Requests run on virtual threads, so the gateway took any number
  of them; once the batches let it take more than the ledger could follow, every overload series of the probe
  ended in an `OutOfMemoryError`, three of three. A heap dump showed about 120 KB per open submission, piling up
  until the heap was full. Now:
  - the heap is about 1 GB, and an `OutOfMemoryError` leaves a heap dump in the pod;
  - at most 1500 submissions are open at once, a wait for the result included, past which a request gets
    `503 busy` at once: 1500 take about 180 MB, and a 1 GB heap would hold about 3,500;
  - inside that, the admission bounds the batch writers' queue: past it a request waits 200 ms, then gets
    `503 busy`. With an admission of 200 a commit that took a little longer filled the queue and refused requests
    while at most 777 were open; with 1000 the same stalls are absorbed;
  - Tomcat takes up to 3000 connections, so the refusal comes from the gateway, not from a connection left waiting.

  No `OutOfMemoryError` since, at any rate measured.
- **Connections.** The gateway's pool grew from 10 to 40 while every request still inserted on its own. With the
  batch writers it no longer limits: at 1600 TPS no request waited for a connection, and at most 13 of 40 were in
  use. All pools together take 91 of PostgreSQL's 97 ordinary connections.

## Tried and rejected

Each was a hypothesis measured against. The first two were tried while every transfer was still a commit of its
own; with batched writes their reason is gone, so they were not repeated.

- **18 Kafka partitions instead of 6.**
  - *Hypothesis:* the ledger was the queue; three times the partitions give three times the consumers.
  - *Measured:* the limit fell from 400–500 to below 300 transfers per second; more consumers met on the same
    accounts, and lock waits became the ledger's largest wait.
  - *Decision:* 6 partitions, two per ledger instance.
- **Group commit** (`commit_delay` in PostgreSQL), set for the gateway's and the ledger's roles.
  - *Hypothesis:* a short delay before the flush of the write-ahead log lets one flush carry several commits.
  - *Measured:* no clear gain with the default, since the delay rarely applied; worse with more eager settings, since
    it lengthened the chain of transactions of one partition.
  - *Decision:* removed; with batched writes there are few commits left to group.

Measured on the current design:

- **Deferring only the credits of the busiest receivers.**
  - *Hypothesis:* the lock waits gather on a few receivers that many senders pay; deferring only their credits
    removes most of them and keeps every other balance up to date at once.
  - *Measured:* worse than deferring none; only deferring every credit kept the ledger up with the gateway. The
    likely reason: once a batch held many transfers, every receiver it touched was a meeting point.
  - *Decision:* every credit is deferred.
- **G1 for every service.**
  - *Hypothesis:* the ledger and the generator, on the Serial collector, stopped for up to 218 ms and 280 ms, and a
    ledger pause in the middle of a batch keeps its senders locked; G1, as in the gateway (up to 78 ms), would
    shorten them.
  - *Measured:* each of the seven JVMs sized its collector for all 6 cores of the node: 77 collector threads on 6
    cores. Under load their collections met, the load average reached 62, a gateway pause took 9.9 s, and three
    pods were restarted by their liveness checks; every run still ended `BALANCED`.
  - *Decision:* G1 for the gateway, with the largest heap and the most allocation; Serial for the others. A
    collector's threads compete for the cores with every process on the node, so it is chosen for the node, not for
    each service alone.

## Where the limit is now

- The gateway spends 53–66 % of its waits on flushing the write-ahead log at commit, and the ledger about a fifth on
  row locks, whose holders are not traced yet.
- The load generator runs on the same node and does not offer much more than 2000.
- The October baseline named the node's CPU as the limit and not the disk; the waits on the write-ahead log say the
  commits limit it as well.

## Not measured yet

- Sustained rates by the October method (two runs of 120 s per rate).
- The share of each change apart.
- Load above what the bank takes, where the limit of open submissions should refuse at once.
