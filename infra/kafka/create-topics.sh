#!/bin/sh
# Single source of truth for topics. Services never create topics; a wrong partition count
# in a service config would otherwise silently add partitions and move keys.
set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-6}"
REPLICAS="${KAFKA_REPLICAS:-1}"
TOPICS="transfers transfer-results"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"

until "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
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
  actual=$(layout_of "$topic")
  if [ "$actual" != "$PARTITIONS $REPLICAS" ]; then
    echo "topic $topic has partitions/replicas '$actual', expected '$PARTITIONS $REPLICAS'" >&2
    exit 1
  fi
done

"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe
