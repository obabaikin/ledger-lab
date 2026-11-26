#!/bin/sh
# Checks the chart with helm template: the setup Jobs' names follow their spec only, the bank's numbers have upper
# bounds, control's pulse options are the chart's load values, the six components split the chart without a gap, and
# only a release told so carries the release check.
set -eu

CHART="$(cd "$(dirname "$0")/../helm/ledger-lab" && pwd)"
failures=0

render() { helm template ledger-lab "$CHART" "$@" 2>/dev/null; }
job_name() {
  job="$1"
  shift
  render "$@" | grep -o "name: $job-[0-9a-f]*" | sed 's/name: //'
}
create_topics() { job_name create-topics "$@"; }
monitor_role() { job_name monitor-role "$@"; }

check() {
  if [ "$2" = "$3" ]; then
    echo "ok: $1"
  else
    echo "FAIL: $1: got '$2', expected '$3'"
    failures=$((failures + 1))
  fi
}
differs() {
  if [ -n "$2" ] && [ "$2" != "$3" ]; then
    echo "ok: $1"
  else
    echo "FAIL: $1: '$2' and '$3' should differ"
    failures=$((failures + 1))
  fi
}
refuses() {
  description="$1"
  expected="$2"
  shift 2
  if output=$(helm template ledger-lab "$CHART" "$@" 2>&1); then
    echo "FAIL: $description: rendered"
    failures=$((failures + 1))
  elif echo "$output" | grep -qF "$expected"; then
    echo "ok: $description"
  else
    echo "FAIL: $description: no '$expected' in: $output"
    failures=$((failures + 1))
  fi
}
renders() {
  description="$1"
  shift
  if output=$(helm template ledger-lab "$CHART" "$@" 2>&1); then
    echo "ok: $description"
  else
    echo "FAIL: $description: $output"
    failures=$((failures + 1))
  fi
}

topics=$(create_topics)
role=$(monitor_role)
differs "the jobs have hash names" "$topics" "create-topics-"
check "same values, same create-topics" "$(create_topics)" "$topics"
check "same values, same monitor-role" "$(monitor_role)" "$role"
check "a new image tag and version leave create-topics" \
  "$(create_topics --set services.control.tag=abc --set version=v9.9.9)" "$topics"
check "a new image tag and version leave monitor-role" \
  "$(monitor_role --set services.control.tag=abc --set version=v9.9.9)" "$role"
differs "more partitions, a new create-topics" "$(create_topics --set kafka.partitions=12)" "$topics"
differs "a longer retention, a new create-topics" "$(create_topics --set retentionHours=5)" "$topics"
check "more partitions leave monitor-role" "$(monitor_role --set kafka.partitions=12)" "$role"
copy=$(mktemp -d)
trap 'rm -rf "$copy"' EXIT
cp -RL "$CHART" "$copy/ledger-lab"
echo "# changed" >>"$copy/ledger-lab/files/create-topics.sh"
original="$CHART"
CHART="$copy/ledger-lab"
changed_topics=$(create_topics)
changed_role=$(monitor_role)
CHART="$original"
differs "a changed create-topics script, a new create-topics" "$changed_topics" "$topics"
check "a changed create-topics script leaves monitor-role" "$changed_role" "$role"
: >"$copy/ledger-lab/files/create-topics.sh"
CHART="$copy/ledger-lab"
refuses "an empty create-topics script" "files/create-topics.sh is empty or missing"
CHART="$original"
differs "a new monitor password, a new monitor-role" "$(monitor_role --set passwords.monitor=other)" "$role"

refuses "accounts.count above a Java int" "accounts.count must be at most 2147483647" --set accounts.count=2147483648
renders "accounts.count of a Java int's highest value" --set accounts.count=2147483647
refuses "retentionHours above a year" "retentionHours must be at most 8760" --set retentionHours=8761
refuses "retentionHours whose milliseconds overflow int64" "retentionHours must be at most 8760" \
  --set retentionHours=3000000000000000000
refuses "retentionHours beyond int64" "retentionHours must be at most 8760" --set-json retentionHours=1e30
refuses "a default pulse outside the options" "load.defaultPulseTps must be one of the options" \
  --set load.defaultPulseTps=950
refuses "a range that is no whole number of steps" "load.maxTps - load.minPulseTps must be a multiple" \
  --set load.maxTps=2050
renders "a base speed off the pulse options' steps" --set load.baseTps=450

control_env() {
  render --show-only templates/services.yaml --set load.minPulseTps=1000 --set load.maxTps=3000 \
    --set load.tpsStep=200 --set load.defaultPulseTps=1400 | grep -F "{name: CONTROL_LOAD_$1," | sed 's/^ *//'
}
check "control's lowest pulse is load.minPulseTps" "$(control_env MINTPS)" '- {name: CONTROL_LOAD_MINTPS, value: "1000"}'
check "control's highest pulse is load.maxTps" "$(control_env MAXTPS)" '- {name: CONTROL_LOAD_MAXTPS, value: "3000"}'
check "control's step is load.tpsStep" "$(control_env TPSSTEP)" '- {name: CONTROL_LOAD_TPSSTEP, value: "200"}'
check "control's first pulse is load.defaultPulseTps" "$(control_env DEFAULTTPS)" \
  '- {name: CONTROL_LOAD_DEFAULTTPS, value: "1400"}'

# Flux builds the chart from git without the symlinks that point out of its directory, so a linked file arrives empty.
check "the chart holds no symlink" "$(find "$CHART" -type l)" ""

COMPONENTS="platform reporting ledger gateway generator control"
# The documents of a render, one per line, in order, so that two renders compare as sets of objects.
documents() {
  python3 -c 'import sys; [print(d.strip().replace("\n", "\\n")) for d in sys.stdin.read().split("\n---\n") if d.strip()]' | sort
}
union() {
  for component in $COMPONENTS; do render --set component="$component" "$@"; echo; done | documents
}
whole=$(render | documents)
check "the six components together are the whole chart" "$(union)" "$whole"
check "the six components hold no object twice" "$(union | wc -l)" "$(printf "%s\n" "$whole" | wc -l)"
server_values="$CHART/../../server/values.yaml"
check "the six components together are the whole chart, with the server's values" \
  "$(union -f "$server_values" --set ingress.host=example.org)" \
  "$(render -f "$server_values" --set ingress.host=example.org | documents)"
kinds() { render --set component="$1" | grep -E '^(kind|  name):' | paste -d' ' - - | sort | tr '\n' ';'; }
check "gateway renders only the gateway" "$(kinds gateway)" "kind: Deployment   name: gateway;kind: Service   name: gateway;"
check "ledger renders only the ledger" "$(kinds ledger)" "kind: Service   name: ledger;kind: StatefulSet   name: ledger;"
check "platform renders no workload of the bank's services" \
  "$(render --set component=platform | grep -E '^(kind|  name):' | paste -d' ' - - |
    grep -cE '^kind: (Deployment|StatefulSet) +name: (ledger|gateway|generator|control|reporting)$' || true)" 0
refuses "an unknown component" "component must be empty, platform or one of" --set component=web
check "a service's release checks the bank's numbers too" \
  "$(helm template ledger-lab "$CHART" --set component=gateway --set retentionHours=8761 2>&1 | grep -c 'at most 8760')" 1

checked() { render --set releaseCheck.enabled=true "$@"; }
check_env() {
  variable="$1"
  shift
  checked --set component=gateway "$@" | grep -F "{name: $variable," | sed 's/^ *//'
}
check "a release that is not told to has no release check" "$(render --set component=gateway | grep -c 'release-check' || true)" 0
check "a release told to check carries the check of its own service only, as its helm test" \
  "$(checked --set component=gateway --show-only templates/release-check.yaml |
    grep -E '^(kind|  name|    helm.sh/hook):' | paste -d' ' - - - | tr -s ' ' | sort | tr '\n' ';')" \
  "kind: ConfigMap name: release-check-gateway helm.sh/hook: test;kind: Job name: release-check-gateway helm.sh/hook: test;"
check "the platform has no release check" "$(checked --set component=platform | grep -c 'release-check' || true)" 0
check "the checks of the six components are the whole chart's" "$(union --set releaseCheck.enabled=true)" \
  "$(checked | documents)"
check "the check looks back over its service's rollout" \
  "$(check_env ROLLOUT_SECONDS --set services.gateway.rolloutSeconds=75 --set services.ledger.rolloutSeconds=200)" \
  '- {name: ROLLOUT_SECONDS, value: "75"}'
check "the check waits releaseCheck.settleSeconds" "$(check_env SETTLE_SECONDS --set releaseCheck.settleSeconds=30)" \
  '- {name: SETTLE_SECONDS, value: "30"}'
check "the check's chaos aftermath is chaosAftermathSeconds" \
  "$(check_env CHAOS_AFTERMATH_SECONDS --set chaosAftermathSeconds=45)" \
  '- {name: CHAOS_AFTERMATH_SECONDS, value: "45"}'
check "control's chaos aftermath is the same chaosAftermathSeconds" \
  "$(render --set component=control --set chaosAftermathSeconds=45 | grep -F '{name: CONTROL_OUTSIDE_CHAOSAFTERMATH,' |
    sed 's/^ *//')" \
  '- {name: CONTROL_OUTSIDE_CHAOSAFTERMATH, value: "45s"}'
check "the check and control ask the same Prometheus" \
  "$(checked | grep -E '\{name: (PROMETHEUS_URL|CONTROL_PROMETHEUS_BASEURL),' | sed 's/.*value: //' | sort -u)" \
  '"http://prometheus:9090"}'
check "the check's image is named with its digest" \
  "$(checked --set component=gateway --show-only templates/release-check.yaml |
    grep -cE '^ +image: python:[0-9]+\.[0-9]+\.[0-9]+-alpine[0-9.]+@sha256:[0-9a-f]{64}$')" 1
check "the check names its service" "$(check_env SERVICE)" '- {name: SERVICE, value: "gateway"}'
check "the check has a memory limit" \
  "$(checked --set component=gateway --set releaseCheck.memory=48Mi --show-only templates/release-check.yaml |
    grep -c 'limits: {memory: 48Mi}')" 1
check "the check carries the chart's script" \
  "$(checked --set component=gateway --show-only templates/release-check.yaml | grep -c 'release_check.py: .*def check(')" 1
refuses "a rollout length of zero" "services.gateway.rolloutSeconds must be above zero" \
  --set component=gateway --set releaseCheck.enabled=true --set services.gateway.rolloutSeconds=0
mkdir -p "$copy/ledger-lab/files/__pycache__" "$copy/packed"
echo bytecode >"$copy/ledger-lab/files/__pycache__/release_check.pyc"
helm package "$copy/ledger-lab" --destination "$copy/packed" >/dev/null
check "the chart carries no bytecode a test run of the script left beside it" \
  "$(tar -tzf "$copy"/packed/*.tgz | grep -c __pycache__ || true)" 0
: >"$copy/ledger-lab/files/release_check.py"
CHART="$copy/ledger-lab"
refuses "an empty release check script" "files/release_check.py is empty or missing" \
  --set component=gateway --set releaseCheck.enabled=true
CHART="$original"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
