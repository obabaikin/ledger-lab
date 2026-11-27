#!/bin/sh
# Single source of truth for topics. Services never create topics; a wrong partition count
# in a service config would otherwise silently add partitions and move keys.
set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-6}"
REPLICAS="${KAFKA_REPLICAS:-1}"
TOPICS="transfers transfer-results"
# As long as the services hold a period's transfers (4 h, ADR 0022): a command read again later is past the ledger's
# retention and is not booked.
RETENTION_MS="${KAFKA_RETENTION_MS:-14400000}"
# Per partition, whichever of the two comes first: the volume holds 8 GiB and cannot grow on an upgrade.
RETENTION_BYTES="${KAFKA_RETENTION_BYTES:-629145600}"
# Retention deletes whole segments only; a segment rolls this often.
SEGMENT_MS="${KAFKA_SEGMENT_MS:-3600000}"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"
# On a fresh cluster the broker's volume is provisioned first.
WAIT_SECONDS="${KAFKA_WAIT_SECONDS:-300}"

deadline=$(($(date +%s) + WAIT_SECONDS))
until "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "kafka at $BOOTSTRAP did not answer within ${WAIT_SECONDS}s" >&2
    exit 1
  fi
  echo "waiting for kafka at $BOOTSTRAP"
  sleep 1
done

# --if-not-exists accepts an existing topic of any shape, so the shape is checked separately.
layout_of() {
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe --topic "$1" \
    | sed -n "s/.*PartitionCount: *\([0-9]*\).*ReplicationFactor: *\([0-9]*\).*/\1 \2/p" | head -n 1
}

for topic in $TOPICS; do
  "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$PARTITIONS" --replication-factor "$REPLICAS"
  "$KAFKA_BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name "$topic" \
    --add-config "retention.ms=$RETENTION_MS,retention.bytes=$RETENTION_BYTES,segment.ms=$SEGMENT_MS"
  actual=$(layout_of "$topic")
  if [ "$actual" != "$PARTITIONS $REPLICAS" ]; then
    echo "topic $topic has partitions/replicas '$actual', expected '$PARTITIONS $REPLICAS'" >&2
    exit 1
  fi
done

"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe
