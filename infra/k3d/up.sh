#!/bin/sh
# Local Kubernetes for development: a k3d cluster the size of the target node, the five service images of the working
# tree, the chart as one release (infra/k3d/flux.sh deploys a commit through Flux instead, as the server does).
# Needs docker, k3d, kubectl and helm on PATH (versions in README). Safe to run again: rebuilds and upgrades.
set -eu

CLUSTER="${CLUSTER:-ledger-lab}"
NAMESPACE="${NAMESPACE:-ledger-lab}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RELEASE=ledger-lab

CLUSTER="$CLUSTER" "$ROOT/infra/k3d/cluster.sh"
if kubectl --namespace "$NAMESPACE" get helmreleases.helm.toolkit.fluxcd.io -o name 2>/dev/null | grep -q .; then
  echo "Flux deploys this cluster (infra/k3d/flux.sh); one release beside its six would own the same objects" >&2
  exit 1
fi

# Each image is tagged with the hash of its module's files as they are now; one the node has already is not built again,
# and a service whose tag stays keeps its pods.
tags=""
for pair in $(CLUSTER="$CLUSTER" "$ROOT/infra/k3d/images.sh"); do
  tags="$tags --set services.${pair%%=*}.tag=${pair#*=}"
done

VERSION="$(git -C "$ROOT" describe --tags --match 'v[0-9]*' --long --always --dirty=+changes)"
# The values set on the cluster before (shorter periods, a smaller bank) stay unless this run sets them again: the
# services refuse to start beside partitions cut for another period length.
previous=""
if helm status "$RELEASE" --namespace "$NAMESPACE" >/dev/null 2>&1; then
  values=$(mktemp)
  trap 'rm -f "$values"' EXIT
  helm get values "$RELEASE" --namespace "$NAMESPACE" -o yaml >"$values"
  if [ "$(cat "$values")" != null ]; then
    previous="-f $values"
  fi
fi
# shellcheck disable=SC2086
helm upgrade --install "$RELEASE" "$ROOT/infra/helm/ledger-lab" --namespace "$NAMESPACE" --create-namespace \
  $previous $tags --set version="$VERSION" --wait --timeout 10m "$@"

NAMESPACE="$NAMESPACE" "$ROOT/infra/k3d/wait-page.sh"
