#!/bin/sh
# Deletes the whole cluster: Kafka's and Postgres' volumes go with it, so offsets and rows never outlive each other.
set -eu
k3d cluster delete "${CLUSTER:-ledger-lab}"
