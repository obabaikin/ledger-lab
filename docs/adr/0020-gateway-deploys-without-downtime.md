# ADR 0020: The gateway deploys without downtime

Status: accepted · Date: 2026-11-08

## Context

A deploy stopped every service before its new version started: for up to a minute nobody could submit a
transfer. The bank is meant to run around the clock, and deploys are its most frequent planned interruption.
The gateway goes first: its API to the clients is stable, so a new version is the same contract on new code.
The ledger, reporting and the generator change their logic in the next phase and follow then.

## Decision

**The new pod takes requests before the old one stops.** The gateway rolls with one extra pod and none
unavailable: the new pod starts and becomes ready; only then the old one is told to stop. It waits 5 s so the
Service stops sending it requests, answers what it holds, a wait for a result included (at most 10 s), and is
gone within 40 s. Its Kafka listeners stop only after that, so a waiting request is still woken and results are
still recorded. The new pod warms up while it already takes requests, as after any start.

**Two gateways at once are safe.** For the seconds of the swap two instances share one database and one
broker, and what they share was built for it (ADR 0006): a key sent to both is one row; each unpublished row
reaches one publisher; each result partition has one recorder; each instance wakes its own waiting requests.
A test runs two instances on the same containers and checks these.

**The swap has the resources it needs.**

- Postgres takes 150 connections: during the swap both pods hold their pools of 40 on top of the other
  services' 44.
- The ledger runs two instances instead of three. A third added no throughput: it used about 120m CPU at
  1300 TPS, the ledger waits on row locks in Postgres. Its memory holds the extra pod, so all memory limits,
  k3s and the OS stay within the server's 12 GB during the swap too.
- Each JVM's heap is its memory less a fixed reserve, 550 MiB for the gateway and 450 MiB for the generator,
  fixed from the start, and glibc's malloc keeps two arenas. Outside the heap the gateway's JVM took about
  290 MiB whatever the load; malloc took up to 135 MiB more under load and kept it, 71 with two arenas. A heap
  set as a share of the memory left a 1024Mi pod 25 MiB of it, and the kernel killed it.

## Considered: two gateways all the time

It would also survive a crash of one. On the server's single node the second replica took about a core from
the ledger and Postgres, and the node's CPU is the limit from about 1300 TPS: the bank slowed. It waits for a
second node.

## Consequences

- A crash of the one gateway still stops submissions until its pod restarts; a deploy no longer does.
- Warming the new pod along the whole path before it takes requests comes with the other services.
- The other services still stop on a deploy.
