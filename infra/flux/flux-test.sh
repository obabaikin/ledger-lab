#!/bin/sh
# Checks release.sh and window.sh by their output: every release rolls back on a failure and detects drift, only a
# service whose tag differs from the pointer before is checked, and the window's jobs open and close it with a memory
# limit and no right beyond the pointer's Kustomization.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
URL=http://git.example.invalid/ledger-lab.git
SERVICES="reporting ledger gateway generator control"
failures=0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

check() {
  if [ "$2" = "$3" ]; then
    echo "ok: $1"
  else
    echo "FAIL: $1: got '$2', expected '$3'"
    failures=$((failures + 1))
  fi
}
# The one object of a manifest that has this name.
object_of() { awk -v name="$2" '/^---$/ { on = 0 } $0 == "  name: " name { on = 1 } on' "$1"; }
checked() {
  for service in $SERVICES; do
    if object_of "$1" "$service" | grep -q '^    releaseCheck: {enabled: true}$'; then printf '%s ' "$service"; fi
  done
}

cd "$HERE/../.."
first="$work/first.yaml"
"$HERE/release.sh" HEAD "$URL" >"$first"
check "six releases" "$(grep -c '^kind: HelmRelease$' "$first")" 6
check "a failed upgrade of any release is rolled back, a failed test too" \
  "$(grep -c '^    remediation: {retries: 0, remediateLastFailure: true, ignoreTestFailures: false}$' "$first")" 6
check "every release detects drift" "$(grep -c '^  driftDetection: {mode: enabled}$' "$first")" 6
check "the service releases run their tests, the platform has none" "$(grep -c '^  test: {enable: true}$' "$first")" 5
check "the platform runs no test" "$(object_of "$first" platform | grep -c '^  test:' || true)" 0
check "without a pointer before, no service is checked" "$(checked "$first")" ""

same="$work/same.yaml"
PREVIOUS_RELEASE="$first" "$HERE/release.sh" HEAD "$URL" >"$same"
check "a service that keeps its tag is not checked" "$(checked "$same")" ""

before="$work/before.yaml"
sed 's/^    services: {gateway: {tag: [0-9a-f]*}}$/    services: {gateway: {tag: 000000000000}}/' "$first" >"$before"
changed="$work/changed.yaml"
PREVIOUS_RELEASE="$before" "$HERE/release.sh" HEAD "$URL" >"$changed"
check "only the service whose tag changed is checked" "$(checked "$changed")" "gateway "

sed '/^    services: {ledger: /d' "$first" >"$before"
PREVIOUS_RELEASE="$before" "$HERE/release.sh" HEAD "$URL" >"$changed"
check "a service the pointer before did not hold is not checked" "$(checked "$changed")" ""

if PREVIOUS_RELEASE="$work/absent.yaml" "$HERE/release.sh" HEAD "$URL" >/dev/null 2>&1; then
  check "a pointer before that cannot be read is refused" rendered refused
else
  check "a pointer before that cannot be read is refused" refused refused
fi

window="$work/window.yaml"
"$HERE/window.sh" >"$window"
schedule_of() { object_of "$1" "$2" | grep -E '^  schedule:|args:' | sed 's/^ *//' | tr '\n' ' '; }
check "the window opens at ten past every hour by resuming the pointer" "$(schedule_of "$window" deploy-window-open)" \
  'schedule: "10 * * * *" args: [resume, kustomization, deploy, --namespace, flux-system] '
check "the window closes at twenty past every hour by suspending the pointer" \
  "$(schedule_of "$window" deploy-window-close)" \
  'schedule: "20 * * * *" args: [suspend, kustomization, deploy, --namespace, flux-system] '
check "both jobs keep UTC" "$(grep -c '^  timeZone: Etc/UTC$' "$window")" 2
check "both jobs have a memory limit" "$(grep -c '^ *limits: {memory: 64Mi}$' "$window")" 2
check "the jobs may read and patch the pointer's Kustomization and nothing else" \
  "$(grep -E '^    (resources|resourceNames|verbs):' "$window" | sed 's/^ *//' | tr '\n' ' ')" \
  'resources: [kustomizations] resourceNames: [deploy] verbs: [get, patch] '
check "the schedules can be moved" \
  "$(WINDOW_OPENS='*/2 * * * *' WINDOW_CLOSES='1-59/2 * * * *' "$HERE/window.sh" | grep -E '^  schedule:' | tr '\n' ' ')" \
  '  schedule: "*/2 * * * *"   schedule: "1-59/2 * * * *" '

if [ "$failures" -gt 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
