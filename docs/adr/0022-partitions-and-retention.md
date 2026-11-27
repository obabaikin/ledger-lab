# ADR 0022: Partitions by period and a retention of 4 hours

Status: accepted · Date: 2026-11-25

## Context

The bank runs around the clock ([ADR 0021](0021-one-bank-around-the-clock.md)): at 200–800 transfers per second every
service writes one or more rows per transfer, without end. A period is reconciled from its own rows within the hour
after it ends; after that nothing reads them. Deleting old rows one by one costs as much write-ahead log and vacuum as
writing them, and a measurement showed what a long delete does to the rest: a cleanup of expired idempotency keys on
the gateway's shared scheduler thread held up its outbox publisher, and p99 went above 1 s
([p99 analysis](../results/2026-11-14-p99-analysis.md)).

## Decision

**Every table of transfers is split by period and dropped whole.** The gateway's `transfers`, the ledger's `transfers`
and `journal_entries`, reporting's `transfer_stats` and the generator's `intents` and `outcomes` are partitioned by
range on the time the row belongs to: when the gateway accepted the transfer, or when the client sent it. One
partition per period, named to the minute of its start. Every instance runs a maintenance task once a minute: it
creates the partitions up to 6 hours ahead and drops each one whose whole period ended longer than the retention ago,
and deletes the gateway's closing rows and reporting's numbers of those periods. 6 hours, not more: every connection
keeps a cache entry per partition, and 313 partitions took 21 MB a connection and Postgres out of its memory (measured
on k3d, 10-minute periods). A partition is created and dropped under a lock timeout of 2 s, each in a transaction of its own, so a busy
table delays one partition, not the bookings. A `DEFAULT` partition takes what no partition covers, so a missing
partition never stops the bank; its rows older than the oldest kept period are deleted, in batches of 10,000 in the
ledger, the gateway and the generator, at once in reporting. The first partitions are made before a service takes its
first transfer.

**4 hours, one setting.** The chart's `retentionHours` (4) sets the retention of every service, the age up to which
control reconciles a period, and the retention of the Kafka topics (`retention.ms`). The topics also stop at 600 MiB a
partition, so they fit their volume under any load. The chart refuses a retention shorter than one period.

**The idempotency window is the retention.** The gateway's keys are a table of their own, not partitioned, as a client
asks by the key alone. A repeated key gets the first answer for 4 hours after the transfer was accepted; after that
the same key is a new transfer. The gateway deletes expired keys once a minute, 10,000 per statement, starting 5
minutes after it starts: a period's keys at once, 1.44 million at 400 per second, would be a burst of log and dead
rows.

**The ledger does not book what is older than its partitions.** A command whose period ended longer than the
retention ago has lost its guard: the row that would show it was booked is gone with its partition, and booking it
again could book it twice. The ledger records nothing for it, publishes no result, logs its id and acceptance time
and counts it in `ledger.transfers.expired`.

## Considered

- **Deleting by age.** The same write-ahead log and dead rows as the inserts, every hour, and a long statement in
  front of every insert that waits behind it.
- **Keeping everything.** Four hours of every service's rows at 400 per second already take 9–12 GB of the
  database's 50 GiB volume by estimate; nothing after the verdict needs them.
- **Keys in the transfers' partitions.** A client sends the key without the acceptance time, so finding it would mean
  a lookup in every partition.

## Consequences

- Not a system of record: a period's transfers, journal and statement are gone about 4 hours after it ends (its Kafka
  messages within 5). Control keeps the period's verdict and report.
- A transfer that was accepted but not booked by the time its partition is dropped is never booked. Its period has
  failed long before: a verdict comes within 40 minutes of the period's end, or the period is `FAILED`.
- A bank stopped for longer than 4 hours cannot reconcile the period that was running when it stopped: control marks
  it `FAILED` with `DETAILS_EXPIRED`, and the commands still waiting in Kafka are
  expired or gone with the topics' retention. A long stop needs the
  retention raised first.
- A client that retries the same key later than 4 hours gets a new transfer, as a client of a real payment API whose
  keys expire would.
