# The gateway deploys without downtime (2026-11-08)

What a deploy without downtime needs on one node, how it was checked, and the bank under load before and after.
The decision is in [ADR 0020](../adr/0020-gateway-deploys-without-downtime.md); the earlier limits are in
[the optimizations](2026-11-06-optimizations.md).

## Result

| | Before (`v0.6.0`) | Now |
|---|---|---|
| A deploy | every service stops; no transfer can be submitted for up to a minute | the gateway is replaced while it takes requests |
| The gateway replaced during a run | — | server, 400 TPS: 12,000 of 12,000 accepted; local cluster, 500 TPS: 15,000 of 15,000; 0 refused, 0 failed, 0 unknown; `BALANCED` |
| Steps of 30 s without a refusal | up to 1300 TPS | up to 2000 TPS in the last staircase; earlier staircases of the same setup refused at 1200–1300 or from 1800 |
| Client p99, 1000–2000 TPS | 0.85–11.8 s | 0.32–2.1 s in the last staircase |
| Memory left under load | gateway 33–55 MiB of its limit, twice killed at 1024Mi; generator killed at 1300–1600 TPS | gateway 199 MiB, generator 144 MiB |
| Reconciliation | every run `BALANCED` | every run of 30 s `BALANCED`; the 5-minute runs' was not computed |

The node's CPU stayed the limit: 4.1–5.3 of 6 cores busy before, 4.4–5.2 now.

## What a deploy without downtime needs

- **Two gateways at once must be safe.** For the seconds of the swap the old and the new pod share one database and
  one broker. A test runs two instances on the same containers: a key sent to both is one transfer, a waiting request is
  woken whichever instance recorded the result, every row is published once, and a request waiting on an instance
  that is stopping is still woken by its result.
- **The old pod drains before it stops.** It waits 5 s for the Service to forget it, answers what it holds, waits for
  results included, and only then stops its Kafka listeners. A review found they stopped first, before the drain; a
  test now closes an instance while a request waits on it.
- **Connections.** Both pods hold their pools during the swap: 124 of Postgres's connections at the peak, so it now
  takes 150 instead of 100.
- **Memory for the extra pod.** The ledger runs two instances instead of three; the third added no throughput (about
  120m CPU per pod at 1300 TPS: the ledger waits on row locks in Postgres). All memory limits, k3s and the OS stay
  within 12 GB during the swap too.

## Memory outside the heap

Two JVMs were killed by the kernel with half their heap free.

**The generator.** Its HTTP client's default executor is an unbounded pool of platform threads. With slow answers at
1300–1600 TPS it grew to 821 threads, about 0.28 MiB each outside the heap. On virtual threads it keeps at most 117
threads up to 2000 TPS, and its memory stays flat.

**The gateway.** Its heap was a share of the memory, fixed from the start. Native Memory Tracking separated the JVM
from the rest:

| | Gateway memory peak (limit 1568 MiB) | Left | The JVM outside its heap | Outside the JVM's own count |
|---|---|---|---|---|
| Heap 1168 MiB | 1535 MiB | 33 MiB | — | — |
| Heap 1068 MiB | 1511 MiB | 57 MiB | about 287 MiB, flat | up to 135 MiB, grew at 1800 TPS and was kept |
| Heap 1068 MiB, glibc malloc with two arenas | 1465 MiB | 103 MiB | about 293 MiB | 71 MiB |
| Heap 1018 MiB, two arenas | 1368 MiB | 199 MiB | — | — |

The JVM's own memory outside the heap stayed at about 290 MiB at any load: G1 72, metaspace 81, JIT code up to 69,
symbols 30, classes 25, threads 7. What grew was glibc's malloc. It keeps an arena per busy thread and gives little
back; limited to two arenas, it held half as much.

**The rule now.** Heap = memory less a fixed reserve (550 MiB for the gateway, 450 for the generator), fixed from the
start, and malloc keeps two arenas. Live data after a collection stayed at most 388 MiB in the gateway and 179 in the
generator.

## The staircase

| TPS | 503 before / now | p50 ms before / now | p99 ms before / now |
|---|---|---|---|
| 1000 | 0 / 0 | 246 / 158 | 1635 / 323 |
| 1100 | 0 / 0 | 207 / 183 | 845 / 634 |
| 1200 | 0 / 0 | 247 / 184 | 931 / 441 |
| 1300 | 0 / 0 | 201 / 225 | 1285 / 1944 |
| 1400 | 1564 / 0 | 670 / 217 | 4420 / 2073 |
| 1500 | 0 / 0 | 265 / 215 | 3729 / 1039 |
| 1600 | 1506 / 0 | 602 / 252 | 6306 / 775 |
| 1700 | 395 / 0 | 908 / 310 | 5940 / 1356 |
| 1800 | 1726 / 0 | 1047 / 322 | 11776 / 1923 |
| 1900 | 0 / 0 | 481 / 330 | 11378 / 1615 |
| 2000 | 1225 / 0 | 1180 / 281 | 10888 / 951 |

## Runs of 5 minutes

| TPS | 503 | Failed (the client gave up) | p50 ms | p99 ms | Ledger: Kafka → posted, p99 |
|---|---|---|---|---|---|
| 1000 | 0 | 0 | 203 | 1,156 | 0.6 s |
| 1300 | 0 | 0 | 194 | 888 | 0.4 s |
| 1400 | 435 | 0 | 270 | 1,338 | 0.7 s |
| 1500 | 669 | 0 | 247 | 1,190 | 0.7 s |
| 1700 | 14,941 | 546 | 481 | 20,922 | 20 s |
| 1800 | 6,856 | 173 | 740 | 20,720 | 20 s |
| 1900 | 10,240 | 277 | 805 | 35,658 | 35 s |
| 2000 | 12,141 | 427 | 889 | 49,587 | 49 s |

## Considered: two gateways all the time

It would also survive a crash of one. On this node the second replica took about a core at 1000 TPS — a second JVM,
a second outbox poller, a second reader of every result — from the ledger and Postgres, and the ledger's posting stage
slowed 5–20 times. It waits for a second node.

## Method

- One VPS, 6 vCPU, 12 GB, k3s; the bank, the load generator and monitoring on it.
- A staircase: one generator run per step, 30 s each, 1000 to 2000 TPS in steps of 100, after about 12 minutes of
  warm-up; 100 accounts per run.
- Runs of 5 minutes: one generator run per rate, each on its own, after the same warm-up.
- The deploy: `kubectl rollout restart` of the gateway 10 s into the run.
- Stages from the timestamps in the database; CPU from `kubectl top` and node-exporter; container memory from cgroup;
  threads from `/proc`; the JVM's memory from Native Memory Tracking.
