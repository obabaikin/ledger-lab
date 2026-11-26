#!/bin/sh
# Checks changed.sh on a scratch repository: a module, common or the chart is something to deploy, docs alone are not.
set -eu

SCRIPT="$(cd "$(dirname "$0")" && pwd)/changed.sh"
NOTHING=3
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q
git config user.email test@example.invalid
git config user.name test

mkdir -p common gateway control infra/docker infra/helm/ledger-lab gradle docs
echo 'implementation(project(":common"))' >gateway/build.gradle.kts
echo 'plugins { java }' >control/build.gradle.kts
echo 'plugins { java }' >common/build.gradle.kts
echo 'class Message' >common/Message.java
echo 'class Gateway' >gateway/Gateway.java
echo 'class Control' >control/Control.java
echo 'FROM jre' >infra/docker/Dockerfile
echo 'replicas: 1' >infra/helm/ledger-lab/values.yaml
mkdir -p infra/kafka infra/helm/ledger-lab/files
echo 'kafka-topics --create' >infra/kafka/create-topics.sh
ln -s ../../../kafka/create-topics.sh infra/helm/ledger-lab/files/create-topics.sh
echo 'include("common", "gateway", "control")' >settings.gradle.kts
echo '' >build.gradle.kts
echo 'notes' >docs/notes.md
echo 'notes' >README.md
git add -A && git commit -qm start

failures=0
fail() {
  echo "FAILED: $1"
  failures=$((failures + 1))
}
commit() {
  echo "$2" >>"$1"
  git add "$1"
  git commit -qm "$1"
}
something() {
  before=$(git rev-parse HEAD)
  commit "$2" "$3"
  if output=$("$SCRIPT" "$before" HEAD); then
    case "$output" in
    *"$4"*) echo "ok: $1" ;;
    *) fail "$1 (expected \"$4\" in: $output)" ;;
    esac
  else
    fail "$1 (nothing to deploy)"
  fi
}
nothing() {
  before=$(git rev-parse HEAD)
  commit "$2" "$3"
  status=0
  output=$("$SCRIPT" "$before" HEAD) || status=$?
  if [ "$status" -eq "$NOTHING" ] && [ "$output" = "nothing to deploy" ]; then
    echo "ok: $1"
  else
    fail "$1 (exit $status: $output)"
  fi
}

nothing "a change to docs alone is nothing to deploy" docs/notes.md 'more notes'
nothing "a change to the README alone is nothing to deploy" README.md 'more notes'
something "a change to a module is something to deploy" control/Control.java 'void run() {}' "control: "
something "a change to common is something to deploy" common/Message.java 'int amount;' "gateway: "
something "a change to the chart is something to deploy" infra/helm/ledger-lab/values.yaml 'replicas: 2' "infra/helm changed"
something "a change to a file the chart links to is something to deploy" infra/kafka/create-topics.sh '# more' "infra/helm changed"
something "a change to the Dockerfile is something to deploy" infra/docker/Dockerfile '# changed' "control: "

before=$(git rev-parse HEAD)
commit control/Control.java 'void stop() {}'
output=$("$SCRIPT" "$before" HEAD) || true
case "$output" in
*gateway*) fail "a module that did not change is not listed ($output)" ;;
*) echo "ok: a module that did not change is not listed" ;;
esac

mkdir -p reporting
echo 'plugins { java }' >reporting/build.gradle.kts
git add reporting/build.gradle.kts
something "a new module is something to deploy" reporting/Reporting.java 'class Reporting' "reporting: none -> "

before=$(git rev-parse HEAD)
git rm -rq reporting
git commit -qm 'drop reporting'
output=$("$SCRIPT" "$before" HEAD) || true
case "$output" in
*"reporting: "*" -> none"*) echo "ok: a removed module is something to deploy" ;;
*) fail "a removed module is something to deploy ($output)" ;;
esac

status=0
"$SCRIPT" HEAD missing >/dev/null 2>&1 || status=$?
if [ "$status" -ne 0 ] && [ "$status" -ne "$NOTHING" ]; then echo "ok: a missing commit is an error, not nothing"; else fail "a missing commit is an error, not nothing (exit $status)"; fi
status=0
"$SCRIPT" HEAD >/dev/null 2>&1 || status=$?
if [ "$status" -ne 0 ] && [ "$status" -ne "$NOTHING" ]; then echo "ok: a wrong call is an error"; else fail "a wrong call is an error (exit $status)"; fi

[ "$failures" -eq 0 ] || { echo "$failures failed"; exit 1; }
echo "all passed"
