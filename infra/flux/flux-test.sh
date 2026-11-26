#!/bin/sh
# Checks release.sh, deployed-release.sh and window.sh by their output: every release rolls back on a failure and
# detects drift, a service is checked when its tag or the chart differs from the pointer deployed in full, that pointer
# is found by the version control reports, and the window's jobs open and close it with a memory limit and no right
# beyond the pointer's Kustomization.
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

# A bank of its own: commits that change chosen modules or the chart, and a deploy branch of pointers to them.
bank="$work/bank"
ALL_SERVICES="reporting ledger gateway generator control "
git init -q "$bank"
cd "$bank"
commit_change() {
  for path in "$@"; do
    mkdir -p "$(dirname "$path")"
    echo "$path" >>"$path"
  done
  git add -A
  git -c user.name=test -c user.email=test@localhost -c commit.gpgsign=false commit -q -m "$*"
  git rev-parse HEAD
}
point() {
  blob=$(git hash-object -w "$1")
  tree=$(printf '100644 blob %s\trelease.yaml\n' "$blob" | git mktree)
  parent=$(git rev-parse --verify --quiet refs/heads/deploy || true)
  git update-ref refs/heads/deploy "$(GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@localhost GIT_COMMITTER_NAME=test \
    GIT_COMMITTER_EMAIL=test@localhost git commit-tree "$tree" ${parent:+-p "$parent"} -m pointer)"
}
version_of() { git describe --tags --match 'v[0-9]*' --long --always "$1"; }

base=$(commit_change reporting/build.gradle.kts ledger-service/build.gradle.kts gateway/build.gradle.kts \
  generator/build.gradle.kts control/build.gradle.kts infra/helm/ledger-lab/values.yaml)
broken=$(commit_change gateway/Broken.java generator/New.java)
fixed=$(commit_change gateway/Fixed.java)
"$HERE/release.sh" "$base" "$URL" >"$work/base.yaml"
point "$work/base.yaml"
PREVIOUS_RELEASE="$work/base.yaml" "$HERE/release.sh" "$broken" "$URL" >"$work/broken.yaml"
point "$work/broken.yaml"

deployed="$work/deployed.yaml"
"$HERE/deployed-release.sh" "$(version_of "$base")" >"$deployed"
check "the deployed pointer is the one holding the version control reports, not the pointer before" \
  "$(grep '^  ref: ' "$deployed")" "  ref: {commit: $base}"
PREVIOUS_RELEASE="$deployed" "$HERE/release.sh" "$fixed" "$URL" >"$work/fixed.yaml"
check "after a rollback a service below the failed one, deployed for the first time, is checked" \
  "$(checked "$work/fixed.yaml")" "gateway generator "
point "$work/fixed.yaml"
"$HERE/release.sh" "$fixed" "$URL" >"$work/again.yaml"
point "$work/again.yaml"
check "of two pointers holding the version the newer is the deployed one" \
  "$("$HERE/deployed-release.sh" "$(version_of "$fixed")" | grep -c '^    releaseCheck: ' || true)" 0
if "$HERE/deployed-release.sh" "$(version_of "$base")-absent" >/dev/null 2>&1; then
  check "a version no pointer holds gives no deployed pointer" found none
else
  check "a version no pointer holds gives no deployed pointer" none none
fi

charted=$(commit_change infra/helm/ledger-lab/values.yaml)
PREVIOUS_RELEASE="$work/again.yaml" "$HERE/release.sh" "$charted" "$URL" >"$work/charted.yaml"
check "a chart changed since the deployed pointer's commit has every service checked" \
  "$(checked "$work/charted.yaml")" "$ALL_SERVICES"
elsewhere=$(commit_change README.md)
PREVIOUS_RELEASE="$work/charted.yaml" "$HERE/release.sh" "$elsewhere" "$URL" >"$work/elsewhere.yaml"
check "a commit that changes neither a tag nor the chart has no service checked" "$(checked "$work/elsewhere.yaml")" ""
PREVIOUS_RELEASE="$work/again.yaml" "$HERE/release.sh" "$elsewhere" "$URL" >"$work/later.yaml"
check "the chart is compared with the deployed pointer's commit, not with the commit before" \
  "$(checked "$work/later.yaml")" "$ALL_SERVICES"
sed 's/^  ref: {commit: [0-9a-f]*}$/  ref: {commit: 0000000000000000000000000000000000000000}/' "$work/charted.yaml" \
  >"$work/unknown.yaml"
PREVIOUS_RELEASE="$work/unknown.yaml" "$HERE/release.sh" "$elsewhere" "$URL" >"$work/blind.yaml" 2>/dev/null
check "a deployed pointer whose commit is not here has every service checked" "$(checked "$work/blind.yaml")" \
  "$ALL_SERVICES"
cd "$HERE/../.."

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
