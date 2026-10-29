#!/bin/sh
# Deploys a release's images from GHCR to the server through the SSH tunnel: cert-manager, then the chart.
# Usage: infra/server/deploy.sh <release tag> [--force] [helm args]. --force deploys while runs are in progress.
# The first deploy on a new domain: add --set ingress.tls.issuer=letsencrypt-staging, then deploy again without it.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

IMAGE_REPOSITORY=ghcr.io/obabaikin/ledger-lab
MODULES="ledger-service gateway generator control reporting"
CERT_MANAGER_CHART="oci://quay.io/jetstack/charts/cert-manager:v1.21.2@sha256:634dce9c13b56677a2c05e2ab76c312d0be2664022d5dd05815da67e1fd5f610"
MANIFEST_TYPES="application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json"
CHART="$ROOT/infra/helm/ledger-lab"
PASSWORDS="$CONFIG_DIR/values-server.yaml"
PLACEHOLDER_PASSWORD=change-me
USAGE="usage: $0 <release tag, vX.Y.Z or vX.Y.Z-rc.N> [--force] [helm args]"
# Mailed invites and the imprint (ADR 0017); the values stay in server.env, outside the repository.
REQUIRED_SETTINGS="TURNSTILE_SITE_KEY TURNSTILE_SECRET MAILJET_API_KEY MAILJET_API_SECRET OPERATOR_NAME OPERATOR_ADDRESS OPERATOR_EMAIL"

RELEASE="${1:-}"
if ! echo "$RELEASE" | grep -Eqx 'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?'; then
  echo "$USAGE" >&2
  exit 2
fi
shift
# A local tag that never reached GitHub has no images; a pushed one is what CI built from.
if ! git -C "$ROOT" ls-remote --exit-code --tags origin "refs/tags/$RELEASE" >/dev/null; then
  echo "$RELEASE is not on GitHub: git tag -a $RELEASE <sha> && git push origin $RELEASE, then wait for CI" >&2
  exit 1
fi
force=false
if [ "${1:-}" = --force ]; then
  force=true
  shift
fi
require_domain
for setting in $REQUIRED_SETTINGS; do
  eval "value=\${$setting:-}"
  if [ -z "$value" ]; then
    echo "$setting is not set: add it to $SERVER_ENV (docs/guides/operations.md, mailed invites)" >&2
    exit 1
  fi
done
require_cluster

# Only a first install skips the check; a database that cannot be asked refuses the deploy like a running run.
if [ "$force" = false ] && helm status ledger-lab --namespace "$NAMESPACE" >/dev/null 2>&1; then
  if ! has_runs=$(psql_admin -tAc "SELECT to_regclass('control.runs') IS NOT NULL") \
    || { [ "$has_runs" = t ] && ! active=$(psql_admin -tAc "SELECT count(*) FROM control.runs WHERE state IN ($ACTIVE_RUN_STATES)"); }; then
    echo "cannot read the runs from Postgres: fix that, or deploy with --force" >&2
    exit 1
  fi
  if [ "$has_runs" = t ] && [ "$active" -gt 0 ]; then
    echo "$active runs in progress: wait for them to finish, or deploy with --force" >&2
    exit 1
  fi
fi

ensure_passwords "$CHART/values.yaml" "$PASSWORDS"

yaml_string() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/''/g")"; }
# mktemp makes it readable by its owner only; it holds the keys.
KEYS=$(mktemp)
trap 'rm -f "$KEYS"' EXIT
# dash runs the EXIT trap on exit, not on a signal; exiting on one runs it.
trap 'exit 1' INT TERM HUP
cat >"$KEYS" <<EOF
invites:
  turnstileSiteKey: $(yaml_string "$TURNSTILE_SITE_KEY")
  mailFrom: $(yaml_string "no-reply@$DOMAIN")
  operator:
    name: $(yaml_string "$OPERATOR_NAME")
    address: $(yaml_string "$OPERATOR_ADDRESS")
    email: $(yaml_string "$OPERATOR_EMAIL")
keys:
  turnstileSecret: $(yaml_string "$TURNSTILE_SECRET")
  mailjetApiKey: $(yaml_string "$MAILJET_API_KEY")
  mailjetApiSecret: $(yaml_string "$MAILJET_API_SECRET")
EOF

# Anonymously, as the server's kubelet pulls them.
for module in $MODULES; do
  path="${IMAGE_REPOSITORY#ghcr.io/}/$module"
  token=$(curl -sf "https://ghcr.io/token?scope=repository:$path:pull" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if ! curl -sf -o /dev/null -I -H "Authorization: Bearer $token" -H "Accept: $MANIFEST_TYPES" \
    "https://ghcr.io/v2/$path/manifests/$RELEASE"; then
    echo "image $IMAGE_REPOSITORY/$module:$RELEASE not found: CI for the tag is not done, or the package is not public" >&2
    exit 1
  fi
done

if [ -n "$(helm list --namespace "$NAMESPACE" --pending --short)" ]; then
  echo "the release is mid-operation: another deploy is running, or one was killed;" \
    "if none is running: helm --namespace $NAMESPACE rollback ledger-lab" >&2
  exit 1
fi

manifest=$(helm template ledger-lab "$CHART" -f "$HERE/values.yaml" -f "$PASSWORDS" -f "$KEYS" --set ingress.host="$DOMAIN" "$@")
if echo "$manifest" | grep -q "$PLACEHOLDER_PASSWORD"; then
  echo "a placeholder password would reach the server: every role in $CHART/values.yaml needs one in $PASSWORDS" >&2
  exit 1
fi

helm upgrade --install cert-manager "$CERT_MANAGER_CHART" \
  --namespace cert-manager --create-namespace -f "$HERE/cert-manager-values.yaml" --wait --timeout 5m
helm upgrade --install ledger-lab "$CHART" --namespace "$NAMESPACE" --create-namespace \
  -f "$HERE/values.yaml" -f "$PASSWORDS" -f "$KEYS" --set image.repository="$IMAGE_REPOSITORY" --set image.tag="$RELEASE" \
  --set ingress.host="$DOMAIN" --wait --wait-for-jobs --timeout 10m "$@"
kubectl --namespace "$NAMESPACE" get pods
echo "control: https://$DOMAIN"

# Updates install without a reboot (apt-unattended-upgrades.conf), so a new kernel waits for one by hand.
if [ -n "${SERVER:-}" ] && ssh -o BatchMode=yes "root@$SERVER" test -f /var/run/reboot-required; then
  echo "the server needs a reboot to finish its updates; between runs: ssh root@$SERVER systemctl reboot" >&2
fi
