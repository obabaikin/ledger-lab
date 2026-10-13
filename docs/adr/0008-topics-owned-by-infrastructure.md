# ADR 0008: Kafka topics are owned by infrastructure

Status: accepted · Date: 2026-10-09 · Updated: 2026-10-09

## Context

Each service declared the topics it used as Spring `NewTopic` beans, with the partition count
in its own configuration. `transfers` was declared by two services, `transfer-results` by one.
That leaves two problems. A consumer that starts before the producer finds no topic: with
broker auto-creation it gets one with the broker's default of one partition, and without it the
listeners fail until the other service comes up. And Spring's `KafkaAdmin` adds partitions when
a declaration asks for more than exist, so a typo in one service's config would silently move
keys to other partitions. Per-account ordering depends on those keys.

## Decision

- Topics are created by an infrastructure step, not by the services: `infra/kafka/create-topics.sh`
  with `--if-not-exists`. Locally it runs as the `kafka-init` service in Compose; on Kubernetes
  it runs as a Job of the Helm chart, and the services that consume wait for the topics in an init
  container.
- The script owns the layout: partition counts and replication factor live only there. An
  existing topic with another layout makes the script fail instead of being accepted silently.
  On Kubernetes the chart's `kafka.partitions` is passed to the script and to control, which starts
  runs only once the ledger holds that many partitions
  ([ADR 0012](0012-invites-quota-and-the-run-queue.md)); locally control's default matches the script's.
- The script also sets retention: 12 hours, shorter than control's 24 hours before it cleans a
  finished run. A consumer group that reads the topics from the start again (a new group, reset or
  expired offsets) then finds only events of runs that still exist, and handling those is idempotent;
  an event of a run already cleaned would bring its rows back, and nothing would remove them.
- Topic names appear in the script and in each service's configuration. A mismatch fails the
  service at start (`missing-topics-fatal`), since nothing may create the missing topic.
- Broker auto-creation is off, so a missing topic is an explicit error, not a silent
  one-partition topic.
- Integration tests create their own topics, since there is no infrastructure step in
  Testcontainers, and run their broker with auto-creation off as well.

## Consequences

- Start order of services no longer matters for topic layout.
- Changing a partition count is a deliberate infrastructure change, which is what it should be:
  it changes which partition each key goes to.
- One more step in local setup, handled by `docker compose up`.
