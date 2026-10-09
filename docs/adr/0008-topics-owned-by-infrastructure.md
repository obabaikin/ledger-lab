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
  it will run as a Helm hook job before the services start.
- The script owns the layout: partition counts and replication factor live only there. An
  existing topic with another layout makes the script fail instead of being accepted silently.
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
