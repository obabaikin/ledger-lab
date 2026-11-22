#!/bin/sh
# Local Kubernetes: a k3d cluster the size of the target node, the five service images, the chart.
# Needs docker, k3d, kubectl and helm on PATH (versions in README). Safe to run again: rebuilds and upgrades.
set -eu

CLUSTER="${CLUSTER:-ledger-lab}"
NAMESPACE="${NAMESPACE:-ledger-lab}"
K3S_IMAGE="${K3S_IMAGE:-rancher/k3s:v1.36.4-k3s1}"
NODE_MEMORY="${NODE_MEMORY:-12g}"
NODE_CPUS="${NODE_CPUS:-6}"
CONTROL_PORT="${CONTROL_PORT:-8083}"
API_PORT="${API_PORT:-6550}"
# service:module, as in the chart's values
SERVICES="ledger:ledger-service gateway:gateway generator:generator control:control reporting:reporting"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

KUBELET_DROP_IN="/var/lib/rancher/k3s/agent/etc/kubelet.conf.d/50-crash-loop-backoff.conf"

if ! k3d cluster list "$CLUSTER" >/dev/null 2>&1; then
  # Docker publishes ports past the host firewall: the Kubernetes API stays on this machine, the page is open to the network.
  k3d cluster create "$CLUSTER" --image "$K3S_IMAGE" --servers 1 --agents 0 \
    --servers-memory "$NODE_MEMORY" --port "$CONTROL_PORT:80@loadbalancer" --api-port "127.0.0.1:$API_PORT" \
    --volume "$ROOT/infra/k3d/kubelet-crash-loop-backoff.yaml:$KUBELET_DROP_IN@server:0" --wait
elif [ "$(docker inspect -f '{{.State.Running}}' "k3d-$CLUSTER-server-0")" = true ] \
  && ! docker exec "k3d-$CLUSTER-server-0" test -f "$KUBELET_DROP_IN"; then
  echo "recreate the cluster (down.sh, up.sh) to cap the crash-loop backoff"
fi

# Pinned to cores, not a CPU quota: with a quota every JVM in the cluster still counts all the host's cores and sizes its
# GC and pools by them; the target server has 6 vCPU.
docker update --cpuset-cpus "0-$((NODE_CPUS - 1))" "k3d-$CLUSTER-server-0" >/dev/null

# Each image is tagged with the hash of its module's files as they are now; one the node has already is not built again,
# and a service whose tag stays keeps its pods.
tags=""
builds=""
images=""
for pair in $SERVICES; do
  service="${pair%%:*}"
  module="${pair#*:}"
  tag=$("$ROOT/infra/ci/module-tag.sh" "$module" --worktree)
  tags="$tags --set services.$service.tag=$tag"
  if ! docker exec "k3d-$CLUSTER-server-0" crictl inspecti "docker.io/ledger-lab/$module:$tag" >/dev/null 2>&1; then
    builds="$builds $module:$tag"
  fi
done
if [ -n "$builds" ]; then
  tasks=""
  for build in $builds; do
    module="${build%%:*}"
    # A jar of an earlier version left in build/libs would give the image build two to choose from.
    rm -f "$ROOT/$module"/build/libs/*.jar
    tasks="$tasks :$module:bootJar"
  done
  # shellcheck disable=SC2086
  (cd "$ROOT" && ./gradlew $tasks -q)
  for build in $builds; do
    module="${build%%:*}"
    docker build -q -f "$ROOT/infra/docker/Dockerfile" -t "ledger-lab/$build" "$ROOT/$module/build/libs"
    images="$images ledger-lab/$build"
  done
  # shellcheck disable=SC2086
  k3d image import --cluster "$CLUSTER" $images
fi

VERSION="$(git -C "$ROOT" describe --tags --match 'v[0-9]*' --long --always --dirty=+changes)"
# shellcheck disable=SC2086
helm upgrade --install ledger-lab "$ROOT/infra/helm/ledger-lab" --namespace "$NAMESPACE" --create-namespace \
  $tags --set version="$VERSION" --wait --timeout 10m "$@"

# k3s installs its ingress controller on its own schedule, after helm already reports the release ready.
INGRESS_ATTEMPTS="${INGRESS_ATTEMPTS:-90}"
attempt=0
until curl -sf -o /dev/null "http://localhost:$CONTROL_PORT/v3/api-docs"; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$INGRESS_ATTEMPTS" ]; then
    echo "control did not answer behind the ingress after $attempt attempts"
    kubectl --namespace "$NAMESPACE" get pods
    exit 1
  fi
  echo "waiting for control behind the ingress"
  sleep 2
done
kubectl --namespace "$NAMESPACE" get pods
echo "control: http://localhost:$CONTROL_PORT"
