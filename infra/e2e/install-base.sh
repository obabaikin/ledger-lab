#!/bin/sh
# Installs the bank of a commit in k3d through Flux (infra/k3d/flux.sh) with the end-to-end values, so that the
# scenario (--base) deploys another commit over it as the server deploys a commit over the one before. The values go
# into the Secret every release reads (infra/flux/release.sh), not into the pointer: a commit from before
# infra/e2e/values.yaml runs the same bank. On a cluster with the bank already, that is a deploy to the commit, started
# once the bank is steady.
# Usage: infra/e2e/install-base.sh <commit>
# Needs what infra/k3d/flux.sh needs.
set -eu

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLUSTER="${CLUSTER:-ledger-lab}"
NAMESPACE="${NAMESPACE:-ledger-lab}"
VALUES="$ROOT/infra/e2e/values.yaml"
# As infra/flux/release.sh names the Secret and Flux the key it reads from it.
SECRET_VALUES=ledger-lab-values
VALUES_KEY=values.yaml
POINTER_SCRIPT=infra/flux/release.sh
LAST_RELEASE=control
# A new node pulls every image of Flux first: on k3d that took 3 to 4 minutes an image.
export FLUX_INSTALL_TIMEOUT="${FLUX_INSTALL_TIMEOUT:-15m}"
# A first install pulls Kafka, Postgres and the rest before its chain of a few minutes; a chain that has stopped is
# not waited for longer.
export CHAIN_TIMEOUT_SECONDS="${CHAIN_TIMEOUT_SECONDS:-1800}"

[ $# -eq 1 ] || { echo "usage: $0 <commit>" >&2; exit 2; }
base=$(git -C "$ROOT" rev-parse --verify --quiet "$1^{commit}") || { echo "no commit $1" >&2; exit 2; }
git -C "$ROOT" cat-file -e "$base:$POINTER_SCRIPT" 2>/dev/null \
  || { echo "$1 has no $POINTER_SCRIPT: from before Flux, too old to install as a base" >&2; exit 2; }

CLUSTER="$CLUSTER" "$ROOT/infra/k3d/cluster.sh"
k() { kubectl --namespace "$NAMESPACE" "$@"; }

running=no
if k get "helmrelease/$LAST_RELEASE" >/dev/null 2>&1; then running=yes; fi
if [ "$running" = yes ]; then
  # The services refuse to start beside partitions cut for another period length.
  if ! k get secret "$SECRET_VALUES" -o "go-template={{index .data \"$VALUES_KEY\"}}" 2>/dev/null | base64 -d | cmp -s - "$VALUES"; then
    echo "the cluster runs a bank with other values than $VALUES: remove it first (infra/k3d/down.sh)" >&2
    exit 1
  fi
  # A release check counts what a bank that has just started still does slowly.
  "$ROOT/infra/e2e/scenario.py" steady --cluster "$CLUSTER" --namespace "$NAMESPACE"
else
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  k create secret generic "$SECRET_VALUES" "--from-file=$VALUES_KEY=$VALUES" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
fi

CLUSTER="$CLUSTER" NAMESPACE="$NAMESPACE" WAIT=1 "$ROOT/infra/k3d/flux.sh" "$base"
