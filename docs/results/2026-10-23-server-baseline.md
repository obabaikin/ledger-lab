# Server baseline: how much load one node takes (2026-10-23)

Measured on the live server at night, the warm-up in the daytime, each run with no other runs. Every number
below comes from a run through the public API, the bank's own report and Prometheus (node-exporter,
postgres-exporter, the services' metrics).

## The machine

| | |
|---|---|
| Node | one VPS, KVM, 6 vCPU (AMD EPYC, 2.0 GHz), 11,960 MB RAM, k3s v1.36.4, kernel 6.8 |
| On it | the whole bank (3 ledger instances, gateway, reporting, control, Kafka, PostgreSQL 17, Prometheus) and the load generator, which plays the clients |
| Disk | `fio`, random 4k writes with an `fsync` after each, the pattern of a write-ahead log: **536 IOPS**, `fsync` p50 1.4 ms, p99 7.7 ms, p99.9 22 ms |

## Method

A rate counts as **sustained** when two runs of 120 s each, on a warm bank, both end with:

- no transfer refused as busy (`503`) and none with an unknown outcome;
- the verdict `BALANCED`;
- the last transfer booked at most 10 s after the clients stopped;
- the clients' p99 latency at most 2 s.

Rates from 100 to 500 transfers per second, then 250 between the last sustained and the first failed one.
Before each run the script checked that no other run was active.

## Results

| TPS | Run | Refused | Gave up | Client p50 / p99 | Drain | Node CPU mean / max | Postgres commits/s | Sustained |
|---|---|---|---|---|---|---|---|---|
| 100 | 1 | 0 | 0 | 0.12 / 0.42 s | 8.5 s | 64 / 86 % | 502 | yes |
| 100 | 2 | 0 | 0 | 0.11 / 0.39 s | 2.3 s | 55 / 67 % | 437 | |
| 200 | 1 | 0 | 0 | 0.14 / 0.93 s | 6.1 s | 68 / 81 % | 710 | **yes** |
| 200 | 2 | 0 | 0 | 0.14 / 0.73 s | 9.2 s | 68 / 82 % | 707 | |
| 250 | 1 | 0 | 0 | 0.12 / 0.47 s | 2.9 s | 70 / 82 % | 864 | no |
| 250 | 2 | 71 | 0 | 0.14 / 1.8 s | 4.3 s | 74 / 84 % | 900 | |
| 300 | 1 | 769 | 66 | 0.17 / 6.1 s | 7.3 s | 75 / 89 % | 943 | no |
| 300 | 2 | 0 | 0 | 0.14 / 1.4 s | 3.7 s | 74 / 85 % | 1,011 | |
| 400 | 1 | 210 | 0 | 2.3 / 5.1 s | 24 s | 78 / 89 % | 1,110 | no |
| 400 | 2 | 126 | 0 | 0.40 / 4.9 s | 20 s | 77 / 88 % | 1,127 | |
| 500 | 1 | 2,398 | 258 | 2.7 / 5.0 s | 61 s | 77 / 89 % | 1,097 | no |
| 500 | 2 | 14,495 | 6,730 | 1.8 / 7.3 s | 37 s | 80 / 92 % | 1,091 | |

"Refused" counts transfers the gateway answered with `503 busy` at least once; "gave up" those the client
stopped retrying. Every run, the failed ones included, ended `BALANCED`: refusing is how the bank stays
correct under overload, and no pod restarted.

**The sustained maximum is 200 transfers per second.** 250 and 300 pass in one run and fail in the other.

## Where the limit is

- **CPU of the node.** It rises from 55–64 % at 100 TPS to 80 % at 500, with peaks of 92 %; at the peak, processes
  wait for a CPU up to 77 % of the time (pressure stall information). The hypervisor takes nothing
  (steal 0), and the disk is not the limit: I/O wait stays at 2–9 %.
- **PostgreSQL is the largest consumer**, 0.8–1.7 cores of the 6. The three ledger instances together and
  the gateway take 0.5–0.8 each, the generator 0.3–0.6, Kafka 0.2–0.35.
- **Commits stop growing at about 1,100 per second**: 400 and 500 TPS commit the same, and the extra load
  turns into refusals and latency.
- Not yet explained: why the services stay at 0.8 cores each above 300 TPS while the node still has some
  CPU left. Lock waits on the few accounts that receive most payments are the next thing to check
  (pending).

## A crash at 75 % of the maximum

150 TPS for 120 s; at second 10 every ledger instance was killed.

- All three instances were ready again after 20.5 s.
- Transfers in flight rose to 2,956 while the ledger was down and were back near 0 47 s after the crash,
  more than a minute before the run ended.
- No transfer refused, none lost: `BALANCED`.

## Right after a deploy (before a fix)

A run of 20 s, started the moment the services were ready:

| | Sent | Refused | Gave up | Unknown | Node CPU mean / max | Gateway waiting for a connection |
|---|---|---|---|---|---|---|
| 300 TPS, the whole bank just deployed | 4,639 of 6,000 | 3,960 | 1,739 | 763 | 85 / 98 % | 551 ms mean |
| 300 TPS, only the gateway restarted | 6,000 | 3,666 | 1,352 | 68 | 81 / 96 % | 264 ms mean |
| 200 TPS, every service restarted | 3,391 of 4,000 | 2,807 | 1,260 | 543 | 91 / 98 % | 787 ms mean |

All three ended `BALANCED`. A warm bank sustains 200 TPS with no refusal, so the rate alone is not the
cause: the JVMs start together on the same six cores, and while they compile and collect garbage (70–125 ms
per second in the gateway and the generator) the gateway's warm-up gets through 519–1,312 of its 2,000
rounds in its 5 s. A lower cap does not fix it.

## Right after a deploy, with the warm-up

The fix: the gateway takes traffic after 5 s and goes on with its warm-up rounds behind that, and control admits no
run until every gateway instance has finished them. Measured the same day, 200 TPS for 20 s, the run started the
moment the deploy or the restart was ready. A rounds value counts as enough when two runs in a row, each after a fresh
start of the gateway, end with nothing refused, given up or unknown.

| Start | Warm-up rounds | Queued | Warm-up took | Refused of 4,000 | Verdict |
|---|---|---|---|---|---|
| the whole bank just deployed | 2,000 | 0 s | 11.5 s | 2,917 (of 3,973 sent) | `BALANCED` |
| only the gateway restarted | 2,000 | 3 s | 10.5 s | 692 | `BALANCED` |
| only the gateway restarted | 4,000 | 1 s / 3 s | 10.5 / 9.3 s | 0 / 257 | `BALANCED` |
| only the gateway restarted | 5,000 | 13 s / 6 s | 17.1 / 11.2 s | 0 / 0 | `BALANCED` |
| only the gateway restarted | 10,000 | 13 s / 24 s | 24.2 / 25.5 s | 0 / 0 | `BALANCED` |
| the whole bank just deployed, 2026-10-24 | 6,000 | 0 s | 15.5 s | 2,890 (of 3,998 sent) | `BALANCED` |
| only the gateway restarted, 2026-10-24 | 6,000 | 9 s / 10 s | 13.4 / 14.8 s | 1 / 0 | `BALANCED` |

- **A cold gateway is fixed by waiting.** From 5,000 rounds on, both runs after a restart refused nothing; the
  visitor waits in the queue for up to 13 s at 5,000 rounds and up to 24 s at 10,000, and the page says the
  bank is warming up. 4,000 passed once and refused 257 times in the second run, 2,000 is not enough at all.
- **"Warm" has to be measured.** With 2,000 rounds the gateway reported itself warm and still refused 692
  transfers: all rounds done is not the same as compiled. The number of rounds is set by measurement on the
  server, not by the time they take.
- **After a full deploy the node's CPU is the limit, not the ledger itself.** The gateway was warm 30 s before
  the run; the ledger instances restart one by one, the last was ready 3 s before control admitted the run.
  Their cold JVMs compiled on the same six cores as PostgreSQL: the node ran at 92–97 %, up to 106 of the
  gateway's inserts waited for a database connection (0–4 with a warm ledger), and the gateway refused 2,917
  transfers. The ledger refuses nothing on its own: it reads from Kafka, and a crash of every instance at
  150 TPS was absorbed with no refusal. What is missing is a warm bank before the first run after a full
  deploy (pending).

## Memory by pod

The highest working set during the whole series, against the pod's limit.

| Pod | Peak / limit, MiB | Note |
|---|---|---|
| PostgreSQL | 729 / 768 (95 %) | the closest to its limit |
| Kafka | 922 / 1,268 (73 %) | |
| gateway | 763 / 1,068 (71 %) | |
| generator (the clients) | 538 / 1,280 (42 %) | |
| ledger, each instance | up to 380 / 512 (74 %) | |
| control | 342 / 448 (76 %) | |
| reporting | 332 / 448 (74 %) | |
| Prometheus | 81 / 512 (16 %) | |
| node-exporter, postgres-exporter | 10 / 64 each | |
| Traefik (k3s) | 112 / 256 (44 %) | |
| metrics server, CoreDNS (k3s) | 71, 63 | no limit |

The node as a whole used 5.2–6.4 GB of its 11.96 GB, with no swap: memory is not the limit.

## Cores by pod

The mean over both runs of a rate, from the kubelet's figures every 5 s.

| Pod | 100 TPS | 200 TPS | 300 TPS | 500 TPS |
|---|---|---|---|---|
| PostgreSQL | 0.95 | 1.16 | 1.33 | 1.66 |
| ledger, three instances together | 0.54 | 0.64 | 0.73 | 0.77 |
| gateway | 0.50 | 0.66 | 0.76 | 0.77 |
| generator (the clients) | 0.35 | 0.38 | 0.43 | 0.50 |
| Kafka | 0.30 | 0.32 | 0.32 | 0.22 |
| reporting | 0.21 | 0.22 | 0.24 | 0.21 |
| control, Prometheus, exporters, k3s pods | 0.1 | 0.1 | 0.1 | 0.1 |
| **All pods, of 6** | **3.0** | **3.5** | **3.9** | **4.2** |

The rest of the CPU the node reports busy (up to 4.8 cores at 500 TPS) is k3s itself and the system.

## What follows from it

- **Rates on the page:** 50, 100 and 200 TPS for one run, at most 300 for all runs together; above 200 runs
  together may meet a refusal now and then, which the bank answers with `503 busy` and still balances.
- **Memory:** PostgreSQL's limit goes from 768 to 912 MiB, a quarter above its peak; every other limit
  already is. k3s's own pods stay without one.
- **Right after a deploy** control admits no run until every gateway instance has finished its warm-up,
  which goes on behind the gateway's readiness for up to 90 s; the queued run says so. On the server the
  warm-up needs at least 5,000 rounds: with them a restarted gateway refused nothing. The server is set to
  6,000, one step above the measured 5,000; after the deploy of 2026-10-24 two runs at 6,000 had 1 and 0 of
  4,000 refused.
- **Right after a full deploy** a run still meets refusals: every JVM starts cold on the same node, and the
  gateway's inserts wait for a database that shares the CPU with them. A restart of the ledger alone, as by the
  chaos button, needs nothing: Kafka holds the transfers until it is back.
