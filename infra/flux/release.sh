#!/bin/sh
# The deploy pointer's content for a commit: the chart's source at that commit and one HelmRelease per part of the bank,
# each waiting for the one before it, so a deploy replaces one service at a time in a fixed order.
# Usage: infra/flux/release.sh <commit> <git URL of the repository> [<values file> ...]; values files are paths in the
# repository at that commit, applied after the chart's own. Prints release.yaml.
# PREVIOUS_RELEASE names the release.yaml of the last pointer whose whole chain was deployed (deployed-release.sh finds
# it), not of the pointer before: after a rollback the services below the failed one still run what that one left them.
# A service whose image tag differs from the one there gets the release check (the chart's
# templates/release-check.yaml); one that keeps its tag, or has none there, gets none and does not wait for it. A chart
# that differs from the one at that pointer's commit can replace any pod, so then every service is checked; so it is
# when that commit is not in this repository and the charts cannot be compared. Without PREVIOUS_RELEASE, as on a first
# install, no service is checked.
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
PREVIOUS_RELEASE="${PREVIOUS_RELEASE:-}"

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
if [ -n "$PREVIOUS_RELEASE" ] && [ ! -r "$PREVIOUS_RELEASE" ]; then
  echo "no previous release file $PREVIOUS_RELEASE" >&2
  exit 2
fi
version=$(git describe --tags --match 'v[0-9]*' --long --always "$commit")

# The lines that carry the chart's commit and a service's tag: written below, read back from the deployed pointer.
# A commit, a tag or a version may be digits alone, which YAML without the quotes reads as a number.
commit_line() { echo "  ref: {commit: \"$1\"}"; }
tag_line() { echo "    services: {$1: {tag: \"$2\"}}"; }
previous_tag() {
  [ -n "$PREVIOUS_RELEASE" ] || return 0
  sed -n "s/^$(tag_line "$1" '\([0-9a-f]*\)')\$/\1/p" "$PREVIOUS_RELEASE"
}

chart_changed=false
if [ -n "$PREVIOUS_RELEASE" ]; then
  deployed=$(sed -n "s/^$(commit_line '\([0-9a-f]*\)')\$/\1/p" "$PREVIOUS_RELEASE")
  if [ -z "$deployed" ] || ! git cat-file -e "$deployed^{commit}" 2>/dev/null; then
    echo "no commit '$deployed' of $PREVIOUS_RELEASE here to compare the chart with: every service is checked" >&2
    chart_changed=true
  elif ! git diff --quiet "$deployed" "$commit" -- ":/$CHART"; then
    chart_changed=true
  fi
fi

cat <<EOF
apiVersion: source.toolkit.fluxcd.io/v1
kind: GitRepository
metadata:
  name: ledger-lab
  namespace: $NAMESPACE
spec:
  interval: $SOURCE_INTERVAL
  url: $url
$(commit_line "$commit")
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
  # A release that fails, by its pods or by its test, goes back to its last revision and stays failed, so the releases
  # after it do not start.
  upgrade:
    remediation: {retries: 0, remediateLastFailure: true, ignoreTestFailures: false}
  driftDetection: {mode: enabled}
EOF
  if [ -n "$tag" ]; then
    echo "  test: {enable: true}"
  fi
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
    version: "$version"
EOF
  if [ -n "$tag" ]; then
    tag_line "$component" "$tag"
    before=$(previous_tag "$component")
    if [ "$chart_changed" = true ] || { [ -n "$before" ] && [ "$before" != "$tag" ]; }; then
      echo "    releaseCheck: {enabled: true}"
    fi
  fi
  previous="$component"
done
