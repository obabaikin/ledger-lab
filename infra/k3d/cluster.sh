#!/bin/sh
# The k3d cluster the size of the target node, created if it is missing, pinned to the target's cores.
# Needs docker and k3d on PATH. Used by up.sh and flux.sh.
set -eu

CLUSTER="${CLUSTER:-ledger-lab}"
K3S_IMAGE="${K3S_IMAGE:-rancher/k3s:v1.36.4-k3s1}"
NODE_MEMORY="${NODE_MEMORY:-12g}"
NODE_CPUS="${NODE_CPUS:-6}"
CONTROL_PORT="${CONTROL_PORT:-8083}"
API_PORT="${API_PORT:-6550}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

KUBELET_DROP_IN="/var/lib/rancher/k3s/agent/etc/kubelet.conf.d/50-crash-loop-backoff.conf"

if ! k3d cluster list "$CLUSTER" >/dev/null 2>&1; then
  # Docker publishes ports past the host firewall: the Kubernetes API stays on this machine, the page is open to the network.
  k3d cluster create "$CLUSTER" --image "$K3S_IMAGE" --servers 1 --agents 0 \
    --servers-memory "$NODE_MEMORY" --port "$CONTROL_PORT:80@loadbalancer" --api-port "127.0.0.1:$API_PORT" \
    --volume "$ROOT/infra/k3d/kubelet-crash-loop-backoff.yaml:$KUBELET_DROP_IN@server:0" --wait
elif [ "$(docker inspect -f '{{.State.Running}}' "k3d-$CLUSTER-server-0")" != true ]; then
  k3d cluster start "$CLUSTER" --wait
elif ! docker exec "k3d-$CLUSTER-server-0" test -f "$KUBELET_DROP_IN"; then
  echo "recreate the cluster (down.sh, up.sh) to cap the crash-loop backoff"
fi

# Pinned to cores, not a CPU quota: with a quota every JVM in the cluster still counts all the host's cores and sizes its
# GC and pools by them; the target server has 6 vCPU.
docker update --cpuset-cpus "0-$((NODE_CPUS - 1))" "k3d-$CLUSTER-server-0" >/dev/null
