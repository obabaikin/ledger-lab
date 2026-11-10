# Kotlin, Java 21 and Java 25 (2026-11-10)

The whole project was ported from Kotlin to Java without changing its behaviour: the same API, schema, messages,
configuration and tests. It was ported to Java 21 first and measured against the Kotlin version, then moved to Java 25
and measured against both.

**The question.** When the project started, Kotlin's main advantages over Java were coroutines, data classes and
sealed hierarchies. Java 21–25 has virtual threads, records and sealed types. The port was done to find out what the
difference is worth now, by measurement: the same API, schema, configuration and tests, one change at a time, both
versions measured side by side on the same server.

**In short:** under load the three versions cannot be told apart. What decides a run at the limit is the server — a
shared VPS whose disk and CPU vary through the day — not the language of the services. A first night series seemed to
show Java doing worse; runs taken in turn under the same conditions did not confirm it.

## Result

| | Kotlin (`v0.8.0`) | Java |
|---|---|---|
| Lines of main code / tests | 7,895 / 9,537 | 11,404 / 11,642 (+44 % / +22 %) |
| Test cases | 451 | 468; every earlier test kept under the same name |
| Static checks | detekt | Error Prone and NullAway in the compiler, warnings are errors |
| Clean build, warm daemon | 4.0–4.5 s with detekt | 13.5–13.8 s (Java 25; 12.7–13.8 s on Java 21) |
| Service jars | 36–60 MB | 29–54 MB |
| Spring start in a full rollout | gateway 21.7 s, ledger 20.1 s, control 16.5 s, reporting 16.7 s | 18.9 s, 17.3 s, 16.1 s, 16.8 s (Java 21) |
| Local cluster, acceptance up to 2000 TPS | 19 of 19 checks | 19 of 19 checks (Java 21) |
| Memory outside the heap | generator 115, gateway 148, ledger 303, reporting 138, control 125 MiB | 90, 129, 272, 127, 114 MiB (Java 21): 8–20 % less |
| Allocation rate at 1500 TPS | gateway 80, generator 53, ledger 71, reporting 25 MiB/s | 84, 54, 71, 25 MiB/s (Java 21): the same |
| Generator threads | 98 | 24 (Java 21) |
| Server, 5 minutes at 1500 TPS, by day | not told apart, see below | not told apart, see below |

The build got slower, not faster: the two checkers run inside every compilation.

## Runs in turn: 5 minutes at 1500 TPS, by day

The bank and the generator are separate programs, so each was compared on its own. Two generators stood side by side
and took turns against one bank, the order swapped every round; then the bank was replaced and the series repeated.

| Bank | Generator | Runs | 503 in all | Runs without a 503 | p99, s, best to worst |
|---|---|---|---|---|---|
| Kotlin | Kotlin | 4 | 8,976 | 0 | 2.7–12.6 |
| Kotlin | Java 21 | 4 | 10,089 | 1 | 1.8–35.6 |
| Java 21 | Kotlin | 4 | 6,497 | 0 | 3.6–17.8 |
| Java 21 | Java 21 | 4 | 10,986 | 1 | 1.4–6.7 |
| Java 21 | Java 21 | 3 | 15,309 | 0 | 3.9–7.1 |
| Java 21 | Java 25 | 3 | 665 | 1 | 1.6–9.0 |

- The Kotlin bank and the Java bank refused 17,765 and 17,483 requests in eight runs each.
- The spread between two runs of one variant is tens of times the difference between variants. The Kotlin version
  on both sides did not have one clean run of four by day; by night it had two of two.
- A run follows the database: where Postgres stalled on the WAL fsync for 10 seconds of the 300, p99 was 1.4–3.5 s
  for either language; where it stalled for 22–28 seconds, both fell apart.
- The generator on Java 25 never filled its 2,000 open requests, on Java 21 it did in every run. Java 21 holds a
  carrier thread while a virtual thread waits for a monitor, and the generator's journal is behind one; Java 24
  removed that. Three pairs of runs are not proof, and the next series shows it does not move the whole.

## Kotlin, Java 21 and Java 25, the whole project, by day

One block per rollout: two warm-up staircases, a staircase 1000–2000 TPS, 5 minutes at 1300 TPS, 5 minutes at
1500 TPS. Java in four blocks in the order 21, 25, 25, 21 between 13:50 and 15:50; Kotlin after them, one block the
same way and one pair of 5-minute runs without a staircase before it.

Runs of 5 minutes:

| Rate | Version | 503 | Failed | p50 ms | p99 ms | Generator: most requests open | Seconds stalled on WAL |
|---|---|---|---|---|---|---|---|
| 1300 | Kotlin, no staircase before | 1,985 | 22 | 311 | 5,869 | 2,000 (its cap) | 13 |
| 1300 | Kotlin | 523 | 0 | 251 | 2,375 | 1,652 | 19 |
| 1300 | Java 21 | 2,059 | 82 | 284 | 2,765 | 2,000 | 15 |
| 1300 | Java 21 | 0 | 0 | 271 | 1,278 | 968 | 18 |
| 1300 | Java 25 | 2,406 | 47 | 333 | 1,833 | 2,000 | 23 |
| 1300 | Java 25 | 3,181 | 26 | 493 | 4,535 | 2,000 | 48 |
| 1500 | Kotlin | 54 | 0 | 345 | 2,028 | 1,681 | 21 |
| 1500 | Kotlin | 52 | 0 | 312 | 2,286 | 1,862 | 8 |
| 1500 | Java 21 | 194 | 0 | 264 | 1,822 | 2,000 | 12 |
| 1500 | Java 21 | 586 | 0 | 334 | 2,529 | 2,000 | 19 |
| 1500 | Java 25 | 0 | 0 | 340 | 2,597 | 1,799 | 10 |
| 1500 | Java 25 | 534 | 0 | 376 | 9,506 | 1,709 | 19 |

Staircases, 30 s per step: refusals (503) · p50 ms · p99 ms.

| TPS | Kotlin | Java 21, first | Java 21, second | Java 25, first | Java 25, second |
|---|---|---|---|---|---|
| 1000 | 0 · 244 · 811 | 0 · 156 · 321 | 0 · 173 · 383 | 0 · 208 · 837 | 0 · 169 · 380 |
| 1100 | 0 · 290 · 965 | 0 · 200 · 908 | 0 · 202 · 801 | 0 · 204 · 547 | 0 · 185 · 1399 |
| 1200 | 0 · 205 · 574 | 0 · 171 · 463 | 0 · 188 · 482 | 0 · 274 · 1509 | 0 · 203 · 603 |
| 1300 | 0 · 247 · 837 | 0 · 220 · 1076 | 0 · 449 · 3477 | 0 · 225 · 846 | 0 · 199 · 782 |
| 1400 | 0 · 243 · 904 | 0 · 200 · 450 | 0 · 360 · 1389 | 0 · 247 · 1096 | 0 · 232 · 624 |
| 1500 | 0 · 358 · 957 | 2 · 334 · 1781 | 0 · 497 · 1550 | 0 · 240 · 760 | 0 · 297 · 3152 |
| 1600 | 0 · 382 · 2678 | 0 · 217 · 616 | 0 · 336 · 1215 | 326 · 496 · 4244 | 100 · 3892 · 17043 |
| 1700 | 0 · 624 · 2788 | 0 · 274 · 1279 | 0 · 574 · 2414 | 0 · 412 · 1183 | 0 · 2974 · 21067 |
| 1800 | 1 · 462 · 1657 | 0 · 207 · 571 | 0 · 463 · 1904 | 0 · 524 · 1978 | 796 · 3055 · 32719 |
| 1900 | 521 · 1185 · 6348 | 5579 · 5175 · 25674 | 0 · 1322 · 15338 | 0 · 496 · 2306 | 4110 · 5755 · 33321 |
| 2000 | 0 · 665 · 10486 | 441 · 5555 · 25565 | 0 · 3026 · 13492 | 0 · 448 · 1761 | 6344 · 9080 · 21642 |

- The versions are not told apart: the ranges overlap at every rate, and each version has both the best and a bad
  block somewhere. Every staircase run reconciled, 55 of 55.
- Short and long runs give different limits. Steps of 30 s pass without a refusal up to 1500 TPS in every block;
  of twelve runs of 5 minutes two passed. A stall of the WAL is rare in 30 s and comes several times in 5 minutes.
- The runs at 1300 TPS did worse than the runs at 1500 TPS that followed them, with or without a staircase before.
  The cause is not found; the order was never swapped.

## The first night series

Four series in the order Kotlin, Java 21, Java 21, Kotlin, one language on both sides, one run of 5 minutes at
1500 TPS in each.

| Series | 503 | Failed | p50 ms | p99 ms | Generator: most requests open |
|---|---|---|---|---|---|
| Kotlin, first | 0 | 0 | 249 | 1,022 | 705 |
| Java 21, first | 518 | 0 | 244 | 2,471 | 2,000 (its cap) |
| Java 21, second | 2,849 | 82 | 259 | 5,941 | 2,000 (its cap) |
| Kotlin, second | 0 | 0 | 259 | 842 | 820 |

Four runs one after another looked like a steady difference. The next morning the Kotlin version alone gave 0 and 109
refusals in two runs, and the series in turn above followed. The lesson is in the method: on a shared server, runs
taken one after another compare the hours, not the versions.

## Night staircases, Kotlin and Java 21

Refusals (503) and client p99 in ms per step of 30 s; two staircases per series.

| TPS | Kotlin, first | Java, first | Java, second | Kotlin, second |
|---|---|---|---|---|
| 1000 | 0 · 389 / 0 · 672 | 0 · 750 / 0 · 653 | 0 · 904 / 0 · 1846 | 0 · 654 / 0 · 1234 |
| 1100 | 0 · 297 / 0 · 1643 | 0 · 253 / 0 · 1243 | 0 · 435 / 0 · 1371 | 0 · 1546 / 0 · 553 |
| 1200 | 0 · 414 / 0 · 439 | 0 · 601 / 0 · 431 | 0 · 643 / 0 · 1642 | 0 · 557 / 0 · 574 |
| 1300 | 0 · 610 / 0 · 839 | 0 · 352 / 0 · 1010 | 0 · 492 / 0 · 2664 | 0 · 996 / 0 · 631 |
| 1400 | 0 · 1607 / 0 · 554 | 0 · 1897 / 0 · 660 | 0 · 1265 / 0 · 665 | 0 · 656 / 0 · 1330 |
| 1500 | 0 · 604 / 0 · 918 | 0 · 542 / 0 · 1470 | 0 · 656 / 0 · 843 | 0 · 1832 / 0 · 670 |
| 1600 | 0 · 856 / 0 · 920 | 553 · 4862 / 0 · 493 | 0 · 968 / 0 · 1704 | 0 · 946 / 0 · 2520 |
| 1700 | 0 · 924 / 0 · 2284 | 0 · 1039 / 0 · 741 | 0 · 979 / 0 · 3784 | 0 · 1886 / 0 · 1270 |
| 1800 | 0 · 1860 / 0 · 2091 | 0 · 1098 / 0 · 1091 | 2322 · 9525 / 0 · 1532 | 0 · 1536 / 341 · 5812 |
| 1900 | 0 · 1071 / 0 · 6765 | 0 · 967 / 0 · 5852 | 8085 · 36844 / 0 · 7180 | 0 · 3738 / 0 · 6755 |
| 2000 | 0 · 8205 / 0 · 2823 | 0 · 2369 / 0 · 10489 | 3888 · 34616 / 0 · 7390 | 0 · 4131 / 452 · 6867 |

The staircases are noisy for both versions: each has steps with a p99 of 5–10 s, and Kotlin's last staircase refused at
1800 and 2000 too. One Java staircase did not recover after 1800 TPS; no Kotlin staircase did that.


## What limits the server

- At 1500 TPS it uses 5.2–5.8 of its 6 cores. The five JVM services take about 2.3 of them; the rest is Postgres,
  Kafka and the system.
- After warm-up the JIT compiler takes about 0.025 of a core for all services together, about 1 % of their CPU. In
  the first three minutes after a rollout it takes more than half of the gateway's and the ledger's. A native image
  would give back the first, not the second.
- Postgres runs checkpoints back to back under this load, and the stalls are backends waiting for one WAL fsync.
- The reconciliation of a 5-minute run timed out in the database in 33 of 34 runs by day, for every version; runs of
  30 s reconciled every time.

## Method

- One VPS, 6 vCPU, 12 GB, k3s; the bank, the load generator and monitoring on it. The same limits, replicas and
  settings for every version.
- A staircase: one generator run per step, 30 s each, 1000 to 2000 TPS in steps of 100; 100 accounts per run.
- Runs in turn: two warm-up staircases per generator, then the 5-minute runs alternate, the order swapped every round.
- Stalls on WAL: seconds in which 8 or more backends waited on `LWLock:WALWrite`, sampled once a second from
  `pg_stat_activity`.
- Memory, allocation and compiler time from the services' own metrics in Prometheus over the windows of the runs.
- The build: `./gradlew clean assemble testClasses`, three runs on a warm daemon.
