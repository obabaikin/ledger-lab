#!/bin/sh
# Deploys a commit's images from GHCR to the server through the SSH tunnel: cert-manager, then the chart.
# Usage: infra/server/deploy.sh <commit sha> [--force] [helm args]. --force deploys while runs are in progress.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

IMAGE_REPOSITORY=ghcr.io/obabaikin/ledger-lab
MODULES="ledger-service gateway generator control reporting"
CERT_MANAGER_VERSION=v1.21.2
MANIFEST_TYPES="application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json"
CHART="$ROOT/infra/helm/ledger-lab"
PASSWORDS="$CONFIG_DIR/values-server.yaml"
USAGE="usage: $0 <40-character commit sha> [--force] [helm args]"

SHA="${1:-}"
if ! echo "$SHA" | grep -Eqx '[0-9a-f]{40}'; then
  echo "$USAGE" >&2
  exit 2
fi
shift
force=false
if [ "${1:-}" = --force ]; then
  force=true
  shift
fi
require_domain
require_cluster

if [ "$force" = false ] && kubectl --namespace "$NAMESPACE" get pod postgres-0 >/dev/null 2>&1 \
  && [ "$(psql_admin -tAc "SELECT to_regclass('control.runs') IS NOT NULL")" = t ]; then
  active=$(psql_admin -tAc "SELECT count(*) FROM control.runs WHERE state IN ($ACTIVE_RUN_STATES)")
  if [ "$active" -gt 0 ]; then
    echo "$active runs in progress: wait for them to finish, or deploy with --force" >&2
    exit 1
  fi
fi

if [ ! -f "$PASSWORDS" ]; then
  roles=$(sed -n '/^passwords:/,/^[^ ]/s/^  \([a-z]*\): .*/\1/p' "$CHART/values.yaml")
  mkdir -p "$CONFIG_DIR"
  (
    umask 077
    {
      echo "passwords:"
      for role in $roles; do
        echo "  $role: $(openssl rand -hex 24)"
      done
    } >"$PASSWORDS"
  )
  echo "generated $PASSWORDS: keep a copy, the database roles are created with these passwords"
fi

# Anonymously, as the server's kubelet pulls them.
for module in $MODULES; do
  path="${IMAGE_REPOSITORY#ghcr.io/}/$module"
  token=$(curl -sf "https://ghcr.io/token?scope=repository:$path:pull" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if ! curl -sf -o /dev/null -I -H "Authorization: Bearer $token" -H "Accept: $MANIFEST_TYPES" \
    "https://ghcr.io/v2/$path/manifests/$SHA"; then
    echo "image $IMAGE_REPOSITORY/$module:$SHA not found, or the package is not public" >&2
    exit 1
  fi
done

if [ -n "$(helm list --namespace "$NAMESPACE" --pending --short)" ]; then
  echo "the release is mid-operation: another deploy is running, or one was killed;" \
    "if none is running: helm --namespace $NAMESPACE rollback ledger-lab" >&2
  exit 1
fi

helm upgrade --install cert-manager oci://quay.io/jetstack/charts/cert-manager --version "$CERT_MANAGER_VERSION" \
  --namespace cert-manager --create-namespace -f "$HERE/cert-manager-values.yaml" --wait --timeout 5m
helm upgrade --install ledger-lab "$CHART" --namespace "$NAMESPACE" --create-namespace \
  -f "$HERE/values.yaml" -f "$PASSWORDS" --set image.repository="$IMAGE_REPOSITORY" --set image.tag="$SHA" \
  --set ingress.host="$DOMAIN" --wait --wait-for-jobs --timeout 10m "$@"
kubectl --namespace "$NAMESPACE" get pods
echo "control: https://$DOMAIN"
