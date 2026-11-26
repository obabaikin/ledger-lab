#!/bin/sh
# The deploy pointer's content for a commit: the chart's source at that commit and one HelmRelease per part of the bank,
# each waiting for the one before it, so a deploy replaces one service at a time in a fixed order.
# Usage: infra/flux/release.sh <commit> <git URL of the repository> [<values file> ...]; values files are paths in the
# repository at that commit, applied after the chart's own. Prints release.yaml.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
NAMESPACE="${NAMESPACE:-ledger-lab}"
CHART=infra/helm/ledger-lab
# The order of a deploy: what the services share, then each service after the ones it calls or that read its output.
CHAIN="platform reporting ledger gateway generator control"
# service:module, as services.<service>.module in the chart's values
MODULES="ledger:ledger-service gateway:gateway generator:generator control:control reporting:reporting"
# Each release in the cluster's own Secret: passwords and keys never reach git.
SECRET_VALUES=ledger-lab-values
# Above the chart's setupWaitSeconds (a setup Job's wait for Kafka or Postgres) and a service's start under load.
RELEASE_TIMEOUT=10m
SOURCE_INTERVAL=1h

if [ $# -lt 2 ]; then
  echo "usage: $0 <commit> <git URL> [<values file> ...]" >&2
  exit 2
fi
commit=$(git rev-parse --verify --quiet "$1^{commit}") || { echo "no commit $1" >&2; exit 2; }
url="$2"
shift 2
for file in "$@"; do
  git cat-file -e "$commit:$file" 2>/dev/null || { echo "no $file at $commit" >&2; exit 2; }
done
version=$(git describe --tags --match 'v[0-9]*' --long --always "$commit")

cat <<EOF
apiVersion: source.toolkit.fluxcd.io/v1
kind: GitRepository
metadata:
  name: ledger-lab
  namespace: $NAMESPACE
spec:
  interval: $SOURCE_INTERVAL
  url: $url
  ref: {commit: $commit}
EOF

previous=""
for component in $CHAIN; do
  tag=""
  for pair in $MODULES; do
    if [ "${pair%%:*}" = "$component" ]; then tag=$("$HERE/../ci/module-tag.sh" "${pair#*:}" "$commit"); fi
  done
  cat <<EOF
---
apiVersion: helm.toolkit.fluxcd.io/v2
kind: HelmRelease
metadata:
  name: $component
  namespace: $NAMESPACE
spec:
  releaseName: $component
  interval: $SOURCE_INTERVAL
  timeout: $RELEASE_TIMEOUT
EOF
  if [ -n "$previous" ]; then
    echo "  dependsOn: [{name: $previous}]"
  fi
  cat <<EOF
  chart:
    spec:
      chart: $CHART
      sourceRef: {kind: GitRepository, name: ledger-lab}
      # The chart's version stays 0.1.0; a new commit is a new chart.
      reconcileStrategy: Revision
      valuesFiles:
        - $CHART/values.yaml
EOF
  for file in "$@"; do
    echo "        - $file"
  done
  cat <<EOF
  valuesFrom:
    - {kind: Secret, name: $SECRET_VALUES, optional: true}
  values:
    component: $component
    version: $version
EOF
  if [ -n "$tag" ]; then
    echo "    services: {$component: {tag: $tag}}"
  fi
  previous="$component"
done
