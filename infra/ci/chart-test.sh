#!/bin/sh
# Checks the chart with helm template: the setup Jobs' names follow their spec only, the bank's numbers have upper
# bounds, control's pulse options are the chart's load values, the six components split the chart without a gap, and
# only a release told so carries the release check, and the alerts: every container's memory against its own limit of
# the values, the node's memory, a deploy that runs late, helm-controller's metric gone missing, where Alertmanager
# mails what, and the subjects and texts of the mails; kube-state-metrics reads the Jobs and the HelmReleases of two
# namespaces alone, a stalled release that was not rolled back is an alert mailed with the highest importance, a failed
# job of the deploy window an alert.
# The rules are run by promtool, Alertmanager's config is read and its mail texts are rendered by amtool: the ones on
# PATH, or the ones in the chart's Prometheus and Alertmanager images through docker.
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
check "the platform has no release check" "$(checked --set component=platform | grep -c 'name: release-check' || true)" 0
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

MEBIBYTE=1048576
# Runs a tool in a directory: tool_run <tool> <the values' key of its image> <directory> <arguments>.
tool_run() {
  tool="$1"
  image_of="$2"
  directory="$3"
  shift 3
  if command -v "$tool" >/dev/null 2>&1; then
    (cd "$directory" && "$tool" "$@")
  else
    docker run --rm --user "$(id -u):$(id -g)" --volume "$directory:/work:ro" --workdir /work --entrypoint "$tool" \
      "$(sed -n "/^$image_of:\$/,/^\$/s/^  image: //p" "$original/values.yaml")" "$@"
  fi
}
promtool_run() { tool_run promtool prometheus "$@"; }
amtool_run() { tool_run amtool alertmanager "$@"; }
rules_of() { render --show-only templates/prometheus-config.yaml "$@" | sed -n '/^  rules.yml: |$/,$p' | sed '1d;s/^    //'; }
# Whether the rules of a render give exactly the alerts a promtool test expects: passed or failed.
rules_give() {
  expected="$1"
  shift
  rules_of "$@" >"$copy/rules/rules.yml"
  printf 'rule_files: [rules.yml]\nevaluation_interval: 1m\ntests:\n%s\n' "$expected" >"$copy/rules/test.yml"
  if promtool_run "$copy/rules" test rules test.yml >"$copy/rules/output" 2>&1; then echo passed; else echo failed; fi
}
mkdir -p "$copy/rules"
rules_of >"$copy/rules/rules.yml"
check "Prometheus loads the rules and finds no two alike" \
  "$(promtool_run "$copy/rules" check rules --lint-fatal rules.yml >/dev/null 2>&1 && echo loaded || echo refused)" loaded

# The memory limit, in bytes, of every container of the chart's Deployments, StatefulSets and DaemonSets ("set"), or
# the limit its rule compares with ("ruled").
memory_limits() {
  which="$1"
  shift
  render "$@" | python3 -c '
import re, sys
units = {"Mi": 2**20, "Gi": 2**30}
found = {}
for document in sys.stdin.read().split("\n---\n"):
    if sys.argv[1] == "ruled":
        rule = r"container_memory_working_set_bytes\{namespace=\"default\", container=\"([^\"]+)\"\}\) > \S+ \* (\d+)$"
        found.update((name, int(limit)) for name, limit in re.findall(rule, document, re.M))
    elif re.search(r"^kind: (Deployment|StatefulSet|DaemonSet)$", document, re.M):
        containers = re.split(r"^ {0,6}\S", document.split("\n      containers:\n", 1)[1], flags=re.M)[0]
        for part in re.split(r"^        - name: ", containers, flags=re.M)[1:]:
            number, unit = re.search(r"limits: \{memory: (\d+)(Mi|Gi)\}", part).groups()
            found[part.split("\n", 1)[0]] = int(number) * units[unit]
print(sorted(found.items()))' "$which"
}
check "the chart's workloads have twelve containers" "$(memory_limits set | grep -o "('" | wc -l)" 12
check "kube-state-metrics is one of them, with the memory limit of the values" \
  "$(memory_limits set --set exporters.kubeState.memory=77Mi | grep -c "('kube-state-metrics', $((77 * MEBIBYTE)))")" 1
check "every container's memory rule compares with that container's own limit" "$(memory_limits ruled)" \
  "$(memory_limits set)"
check "the rules follow limits changed in the values" \
  "$(memory_limits ruled --set services.ledger.memory=1Gi --set kafka.memory=999Mi)" \
  "$(memory_limits set --set services.ledger.memory=1Gi --set kafka.memory=999Mi)"
refuses "a memory limit the rule cannot turn into bytes" "a memory limit must be a whole number of Mi or Gi" \
  --set services.ledger.memory=0.5Gi

ledger_limit=$(sed -n '/^  ledger:$/,/^  gateway:$/s/^    memory: \([0-9]*\)Mi$/\1/p' "$CHART/values.yaml")
above=$((ledger_limit * MEBIBYTE * 91 / 100))
below=$((ledger_limit * MEBIBYTE * 89 / 100))
memory_test() {
  cat <<EOF
  - interval: 1m
    input_series:
      - series: 'container_memory_working_set_bytes{namespace="default", pod="ledger-0", container="ledger"}'
        values: '$1x10'
      - series: 'container_memory_working_set_bytes{namespace="default", pod="gateway-1", container="gateway"}'
        values: '$1x10'
      - series: 'container_memory_working_set_bytes{namespace="other", pod="ledger-0", container="ledger"}'
        values: '${above}x10'
    alert_rule_test:
      - eval_time: ${3:-5m}
        alertname: ContainerMemoryNearLimit
        exp_alerts: $2
EOF
}
ledger_alert="
          - exp_labels: {namespace: default, pod: ledger-0, container: ledger}
            exp_annotations:
              summary: Container ledger of pod ledger-0 uses more than SHARE of its memory limit of LIMIT"
ledger_alerts() { echo "$ledger_alert" | sed "s/SHARE/$1/;s/LIMIT/$2/"; }
check "a container above 0.9 of its limit in the values is an alert, the same bytes in a container with a higher limit are none" \
  "$(rules_give "$(memory_test "$above" "$(ledger_alerts 0.9 "${ledger_limit}Mi")")")" passed
check "a container below 0.9 of its limit is no alert" "$(rules_give "$(memory_test "$below" '[]')")" passed
check "the same bytes are no alert once the values give the container a higher limit" \
  "$(rules_give "$(memory_test "$above" '[]')" --set services.ledger.memory=2000Mi)" passed
check "a container above 0.9 of its limit for less than the 2 minutes of alerts.heldFor is no alert yet" \
  "$(rules_give "$(memory_test "$above" '[]' 1m)")" passed
check "the time a container stays above before it is an alert is alerts.heldFor" \
  "$(rules_give "$(memory_test "$above" '[]')" --set alerts.heldFor=10m)" passed
check "the share of the limit is alerts.memory.limitShare" \
  "$(rules_give "$(memory_test "$below" "$(ledger_alerts 0.8 "${ledger_limit}Mi")")" --set alerts.memory.limitShare=0.8)" passed

node_test() {
  cat <<EOF
  - interval: 1m
    input_series:
      - series: 'node_memory_MemAvailable_bytes{job="node-exporter"}'
        values: '$(($1 * MEBIBYTE))x10'
    alert_rule_test:
      - eval_time: 5m
        alertname: NodeMemoryLow
        exp_alerts: $2
EOF
}
node_alert='
          - exp_labels: {job: node-exporter}
            exp_annotations: {summary: The node has less than 300 MiB of memory available}'
check "a node with less than 300 MiB available is an alert" "$(rules_give "$(node_test 299 "$node_alert")")" passed
check "a node with 300 MiB available is no alert" "$(rules_give "$(node_test 300 '[]')")" passed

# promtool's clock starts at 00:00 UTC; the worker's samples are busy, then idle, as the pattern says.
deploy_test() {
  cat <<EOF
  - interval: 15s
    input_series:
      - series: 'controller_runtime_active_workers{job="helm-controller", controller="helmrelease"}'
        values: '$1'
    alert_rule_test:
      - eval_time: $2
        alertname: DeployRunsLate
        exp_alerts: $3
EOF
}
late_alert='
          - exp_annotations: {summary: A deploy is still running at minute 55 of the hour}'
check "a release still being upgraded at :56 is an alert" "$(rules_give "$(deploy_test '1x240' 56m "$late_alert")")" passed
check "a release being upgraded at :54 is no alert" "$(rules_give "$(deploy_test '1x240' 54m '[]')")" passed
check "no release being upgraded at :56 is no alert" "$(rules_give "$(deploy_test '0x240' 56m '[]')")" passed
check "a look at an unchanged release at :56 is no alert" \
  "$(rules_give "$(deploy_test '0x222 1 0x17' 56m '[]')")" passed
check "the half minute between two releases of a chain at :56 leaves the alert" \
  "$(rules_give "$(deploy_test '1x220 0 0 1 1' 56m "$late_alert")")" passed
# The last 2 minutes before :56 are the 8 samples up to the one at :56 itself.
check "a worker busy at :56 itself, as for a look at an unchanged release, and idle for the 2 minutes before is no alert" \
  "$(rules_give "$(deploy_test '0x223 1 0x16' 56m '[]')")" passed
check "a worker idle at :56 itself that was busy for more than half of the 2 minutes before is an alert" \
  "$(rules_give "$(deploy_test '1x222 0 0 1x16' 56m "$late_alert")")" passed
check "the share of the time the worker has to be busy is alerts.deploy.busyShare" \
  "$(rules_give "$(deploy_test '1x222 0 0 1x16' 56m '[]')" --set alerts.deploy.busyShare=0.8)" passed
check "the time the worker is looked at is alerts.deploy.busyWindow" \
  "$(rules_give "$(deploy_test '1x222 0 0 1x16' 56m '[]')" --set alerts.deploy.busyWindow=30s)" passed
check "the minute a deploy is late from is alerts.deploy.deadlineMinute" \
  "$(rules_give "$(deploy_test '1x240' 54m "$(echo "$late_alert" | sed 's/55/50/')")" --set alerts.deploy.deadlineMinute=50)" \
  passed
check "Prometheus reads helm-controller's workers in Flux's namespace" \
  "$(render --show-only templates/prometheus-config.yaml --set flux.namespace=flux-elsewhere |
    grep -A6 'job_name: flux' | grep -c 'names: \[flux-elsewhere\]')" 1
flux_scrape=$(render --show-only templates/prometheus-config.yaml |
  sed -n '/^      - job_name: flux$/,/^      - job_name: \|^    rule_files:/p')
check "of Flux's pods Prometheus keeps helm-controller's metrics port, and names the job after the pod's label app" \
  "$(echo "$flux_scrape" | sed -n '/^        relabel_configs:$/,/^        metric_relabel_configs:$/p' | sed '1d;$d;s/^ *//' |
    tr '\n' ' ')" \
  "- source_labels: [__meta_kubernetes_pod_label_app] regex: helm-controller action: keep \
- source_labels: [__meta_kubernetes_pod_container_port_name] regex: http-prom action: keep \
- source_labels: [__meta_kubernetes_pod_label_app] target_label: job \
- source_labels: [__meta_kubernetes_pod_name] target_label: instance "
workers_metric=$(echo "$flux_scrape" | sed -n '/^        metric_relabel_configs:$/,$p' | sed -n 's/^ *regex: //p')
check "of helm-controller's metrics Prometheus keeps the busy workers alone" "$workers_metric" \
  controller_runtime_active_workers
check "the deploy's two rules read that metric of the job helm-controller" \
  "$(rules_of | grep -c "$workers_metric{job=\"helm-controller\", controller=\"helmrelease\"}")" 2
# The rule's samples of the worker, or none when the pattern is empty.
missing_test() {
  cat <<EOF
  - interval: 15s
    input_series: $([ -n "$1" ] || echo '[]')
$([ -z "$1" ] || printf "      - series: 'controller_runtime_active_workers{job=\"helm-controller\", controller=\"helmrelease\"}'\n        values: '%s'" "$1")
    alert_rule_test:
      - eval_time: $2
        alertname: DeployMetricMissing
        exp_alerts: $3
EOF
}
missing_alert="
          - exp_labels: {job: helm-controller, controller: helmrelease}
            exp_annotations:
              summary: \"Prometheus does not get helm-controller's busy workers: a deploy that runs late would not be alerted\""
check "helm-controller's busy workers missing for the 2 minutes of alerts.heldFor is an alert" \
  "$(rules_give "$(missing_test '' 5m "$missing_alert")")" passed
check "helm-controller's busy workers missing for less than that is no alert yet" \
  "$(rules_give "$(missing_test '' 1m '[]')")" passed
check "helm-controller's busy workers there, busy or idle, are no alert" \
  "$(rules_give "$(missing_test '0x10 1x10' 5m '[]')")" passed
check "helm-controller's busy workers gone after they were there are an alert" \
  "$(rules_give "$(missing_test '0x4 stale' 10m "$missing_alert")")" passed

# The conditions of the release gateway as kube-state-metrics gives them: "<type>:<reason>:<values>" each.
stalled_test() {
  eval_time="$1"
  expected="$2"
  shift 2
  echo "  - interval: 15s"
  echo "    input_series:"
  for condition in "$@"; do
    echo "$condition" | awk -F: '{ printf "      - series: %sflux_helmrelease_condition{namespace=\"%s\", name=\"gateway\", type=\"%s\", reason=\"%s\"}%s\n        values: %s%s%s\n", q, ($4 == "" ? "default" : $4), $1, $2, q, q, $3, q }' q="'"
  done
  cat <<EOF
    alert_rule_test:
      - eval_time: $eval_time
        alertname: DeployStalled
        exp_alerts: $expected
EOF
}
stalled_alert() {
  echo "
          - exp_labels: {severity: critical, namespace: default, name: gateway, reason: $1}
            exp_annotations:
              summary: \"Release gateway is stalled ($1) and was not rolled back: the deploy waits for a hand\""
}
check "a release stalled without a rollback, as after a node restart in a rollout, is an alert of the highest importance" \
  "$(rules_give "$(stalled_test 5m "$(stalled_alert MissingRollbackTarget)" 'Stalled:MissingRollbackTarget:1x40' \
    'Ready:MissingRollbackTarget:0x40')")" passed
check "a release stalled after its rollback is no such alert: it runs its old version and Flux's events mailed it" \
  "$(rules_give "$(stalled_test 5m '[]' 'Stalled:RetriesExceeded:1x40' 'Remediated:RollbackSucceeded:1x40')")" passed
check "a release stalled after its rollback failed is an alert" \
  "$(rules_give "$(stalled_test 5m "$(stalled_alert RetriesExceeded)" 'Stalled:RetriesExceeded:1x40' \
    'Remediated:RollbackFailed:0x40')")" passed
check "a release that is not stalled is no alert, not ready as it may be" \
  "$(rules_give "$(stalled_test 5m '[]' 'Stalled:Progressing:0x40' 'Ready:DependencyNotReady:0x40')")" passed
check "a release stalled for less than the 2 minutes of alerts.heldFor is no alert yet" \
  "$(rules_give "$(stalled_test 1m '[]' 'Stalled:MissingRollbackTarget:1x40')")" passed
check "a stalled release of another namespace is not this bank's alert" \
  "$(rules_give "$(stalled_test 5m '[]' 'Stalled:MissingRollbackTarget:1x40:other' 'Ready:Succeeded:1x40')")" passed
state_missing_alert="
          - exp_annotations:
              summary: \"Prometheus does not get the conditions of the releases from kube-state-metrics: a stalled deploy would not be alerted\""
state_missing_test() {
  stalled_test "$1" "$2" "$3" | sed 's/alertname: DeployStalled/alertname: DeployStateMissing/'
}
check "the releases' conditions missing for the 2 minutes of alerts.heldFor are an alert" \
  "$(rules_give "$(state_missing_test 5m "$(echo "$state_missing_alert" | sed 's/exp_annotations/exp_labels: {namespace: default}\n            exp_annotations/')" 'Ready:Succeeded:1x40:other')")" passed
check "the releases' conditions there, whatever they say, are no such alert" \
  "$(rules_give "$(state_missing_test 5m '[]' 'Ready:Succeeded:1x40')")" passed
check "the rule and kube-state-metrics name the releases' conditions alike" \
  "$(render --show-only templates/kube-state-metrics.yaml | grep -A3 '^          metricNamePrefix: ""$' >/dev/null &&
    render --show-only templates/kube-state-metrics.yaml | sed -n 's/^            - name: //p')" \
  "$(rules_of | sed -n 's/^ *expr: absent(\([a-z_]*\){namespace="default"})$/\1/p')"

# A job of the window: window_test <eval time> <expected> <job> <its Failed condition true> <... false> [<namespace>].
window_test() {
  cat <<EOF
  - interval: 1m
    input_series:
      - series: 'kube_job_failed{namespace="${6:-flux-system}", job_name="$3", condition="true"}'
        values: '$4x200'
      - series: 'kube_job_failed{namespace="${6:-flux-system}", job_name="$3", condition="false"}'
        values: '$5x200'
      - series: 'kube_job_status_start_time{namespace="${6:-flux-system}", job_name="$3"}'
        values: '600x200'
    alert_rule_test:
      - eval_time: $1
        alertname: DeployWindowJobFailed
        exp_alerts: $2
EOF
}
window_alert='
          - exp_labels: {namespace: flux-system, job_name: deploy-window-close-29384756, condition: "true"}
            exp_annotations:
              summary: "Job deploy-window-close-29384756 of the deploy window failed: the window may be left as it was, open or closed"'
check "a failed job of the deploy window is an alert" \
  "$(rules_give "$(window_test 30m "$window_alert" deploy-window-close-29384756 1 0)")" passed
check "a job of the window that did not fail is no alert" \
  "$(rules_give "$(window_test 30m '[]' deploy-window-close-29384756 0 1)")" passed
check "a failed job of the window is no alert an hour after it started: the next run of its kind is due" \
  "$(rules_give "$(window_test 71m '[]' deploy-window-close-29384756 1 0)")" passed
check "the time a failed job of the window stays an alert is alerts.deploy.windowJobAlertSeconds" \
  "$(rules_give "$(window_test 30m '[]' deploy-window-close-29384756 1 0)" --set alerts.deploy.windowJobAlertSeconds=600)" passed
check "another failed job of Flux's namespace is not the window's alert" \
  "$(rules_give "$(window_test 30m '[]' some-other-job 1 0)")" passed
check "a failed job of that name in another namespace is not the window's alert" \
  "$(rules_give "$(window_test 30m '[]' deploy-window-close-29384756 1 0 default)")" passed
check "the window's jobs are looked for in Flux's namespace of the values" \
  "$(rules_give "$(window_test 30m "$(echo "$window_alert" | sed 's/flux-system/flux-elsewhere/')" deploy-window-close-29384756 1 0 \
    flux-elsewhere)" --set flux.namespace=flux-elsewhere)" passed

kube_state() { render --show-only templates/kube-state-metrics.yaml "$@"; }
check "kube-state-metrics is the ready-made one, named by digest" \
  "$(kube_state | grep -cE '^ +image: registry.k8s.io/kube-state-metrics/kube-state-metrics:v[0-9.]+@sha256:[0-9a-f]{64}$')" 1
check "of its own kinds kube-state-metrics reads the Jobs alone" "$(kube_state | grep -c '^ *- --resources=jobs$')" 1
check "kube-state-metrics reads the bank's namespace and Flux's, no other" \
  "$(kube_state --namespace bank --set flux.namespace=flux-elsewhere | sed -n 's/^ *- --namespaces=//p')" "bank,flux-elsewhere"
check "of the custom kinds kube-state-metrics reads the HelmRelease alone, each condition with its type and reason" \
  "$(kube_state | sed -n '/^kind: ConfigMap$/,/^---$/p' | grep -E 'groupVersionKind|path:|type:|reason:|valueFrom:' |
    sed 's/^ *//' | tr '\n' ' ')" \
  "- groupVersionKind: {group: helm.toolkit.fluxcd.io, version: v2, kind: HelmRelease} type: Gauge \
path: [status, conditions] type: [type] reason: [reason] valueFrom: [status] "
check "in the bank's namespace kube-state-metrics may list and watch the Jobs and the HelmReleases, and nothing else" \
  "$(kube_state | sed -n '/^kind: Role$/,/^---$/p' | sed -n '/^rules:$/,$p' | grep -v '^$\|^---$' | sed 's/^ *//' | tr '\n' ' ')" \
  "rules: - apiGroups: [\"batch\"] resources: [\"jobs\"] verbs: [\"list\", \"watch\"] \
- apiGroups: [\"helm.toolkit.fluxcd.io\"] resources: [\"helmreleases\"] verbs: [\"list\", \"watch\"] "
check "over the whole cluster kube-state-metrics may list and watch the CustomResourceDefinitions, and nothing else" \
  "$(kube_state | sed -n '/^kind: ClusterRole$/,/^---$/p' | sed -n '/^rules:$/,$p' | grep -v '^$\|^---$' | sed 's/^ *//' |
    tr '\n' ' ')" \
  'rules: - apiGroups: ["apiextensions.k8s.io"] resources: ["customresourcedefinitions"] verbs: ["list", "watch"] '
check "the chart gives kube-state-metrics no other right" "$(kube_state | grep -c '^kind: \(Cluster\)\?Role$')" 2
check "Prometheus scrapes kube-state-metrics as it does the exporters: by the annotation and the port named metrics" \
  "$(kube_state | grep -c -e '^        prometheus.io/scrape: "true"$' -e '^            - {name: metrics, containerPort: 8080}$')" 2
check "kube-state-metrics belongs to the platform's release alone" \
  "$(for part in platform gateway; do kube_state --set component=$part | grep -c '^kind: Deployment$' || true; done | tr '\n' ' ')" \
  "1 0 "
check "the chart's right over the whole cluster is the nodes and their metrics; the pods of Flux are not the chart's to read" \
  "$(render --show-only templates/prometheus.yaml | sed -n '/^kind: ClusterRole$/,/^---$/p' | sed -n 's/^ *resources: //p' |
    tr '\n' ' ')" \
  '["nodes"] ["nodes/metrics"] '
check "Prometheus sends its alerts to Alertmanager" \
  "$(render --show-only templates/prometheus-config.yaml --set alertmanager.port=9999 | grep -A3 'alertmanagers:' |
    grep -c 'targets: \["alertmanager:9999"\]')" 1
check "the certificate's alert stays with cert-manager on" \
  "$(rules_of --set certManager.enabled=true | grep -c 'alert: CertificateExpiresSoon')" 1

alertmanager() { render --show-only templates/alertmanager.yaml "$@"; }
mailed() {
  alertmanager --set alertmanager.mail.to=deploy@example.invalid --set alertmanager.mail.from=no-reply@example.invalid \
    --set alertmanager.mail.smarthost=smtp.example.invalid:587 "$@"
}
check "Alertmanager has a memory limit" \
  "$(alertmanager --set alertmanager.memory=48Mi | grep -c 'limits: {memory: 48Mi}')" 1
check "without an address nothing is mailed" "$(alertmanager | grep -c 'email_configs\|smtp_' || true)" 0
check "with an address the alerts, the ones that wait for a hand, a deploy's good end and its failure are mailed there" \
  "$(mailed | grep -c 'to: "deploy@example.invalid"')" 4
refuses "an address to mail without a server" "alertmanager.mail.smarthost is needed" \
  --set alertmanager.mail.to=deploy@example.invalid --set alertmanager.mail.from=no-reply@example.invalid
check "the SMTP login is the invites' Mailjet keys unless the values name another" \
  "$(mailed --set keys.mailjetApiKey=key --set keys.mailjetApiSecret=secret | grep 'smtp_auth' | sed 's/^ *//' | tr '\n' ' ')" \
  'smtp_auth_username: "key" smtp_auth_password: "secret" '
check "a deploy's good end is the passed test of the chain's last release" \
  "$(mailed --set alertmanager.deploy.lastRelease=last | grep -B1 'receiver: deploy-done' | head -1 | sed 's/^ *//')" \
  "- matchers: ['name=\"last\"', 'reason=\"TestSucceeded\"']"
check "a failed upgrade, a failed test and a rollback of one release make one group of mails" \
  "$(mailed | grep -B1 -A2 'receiver: deploy-failed' | sed 's/^ *//' | tr '\n' ' ')" \
  "- matchers: ['reason=~\"InstallFailed|UpgradeFailed|TestFailed|RollbackSucceeded|RollbackFailed\"'] \
receiver: deploy-failed group_by: [name] group_wait: 2m "
check "Flux's other events are not mailed" \
  "$(mailed | grep -A1 "matchers: \['kind=\"HelmRelease\"'\]" | tail -1 | sed 's/^ *//')" 'receiver: unmailed'
check "a Flux event outlives the timeout of a release, so a failure is there for its rollback's mail" \
  "$(mailed | grep -c '^      resolve_timeout: 15m$')" 1
mkdir -p "$copy/alertmanager"
mailed | python3 -c '
import json, sys
config, texts = sys.stdin.read().split("\n---\n")[0].split("  alertmanager.yml: |\n", 1)[1].split("\n  mail.tmpl: ", 1)
texts = json.loads(texts.strip().split("\n")[0])
config = "\n".join(line[4:] for line in config.split("\n")).replace("/etc/alertmanager/", "")
open(sys.argv[1] + "/alertmanager.yml", "w").write(config + "\n")
open(sys.argv[1] + "/mail.tmpl", "w").write(texts)' "$copy/alertmanager"
check "Alertmanager loads its config and the mail texts" \
  "$(amtool_run "$copy/alertmanager" check-config alertmanager.yml >/dev/null 2>&1 && echo loaded || echo refused)" loaded
receiver_of() { amtool_run "$copy/alertmanager" config routes test --config.file=alertmanager.yml "$@" 2>&1 | tail -1; }
check "the passed test of the chain's last release is mailed as the deploy's good end" \
  "$(receiver_of kind=HelmRelease name=control reason=TestSucceeded)" deploy-done
check "the passed test of a release before the last is not mailed" \
  "$(receiver_of kind=HelmRelease name=gateway reason=TestSucceeded)" unmailed
check "the last release's upgrade, its test still ahead, is not mailed" \
  "$(receiver_of kind=HelmRelease name=control reason=UpgradeSucceeded)" unmailed
for reason in InstallFailed UpgradeFailed TestFailed RollbackSucceeded RollbackFailed; do
  check "$reason of a release is mailed as a failed deploy" "$(receiver_of kind=HelmRelease name=gateway "reason=$reason")" \
    deploy-failed
done
check "an alert of Prometheus is mailed as an alert" "$(receiver_of alertname=NodeMemoryLow)" alerts
check "helm-controller's metric gone missing is mailed as an alert" "$(receiver_of alertname=DeployMetricMissing)" alerts
urgent_severity=$(rules_of | sed -n '/alert: DeployStalled$/,/alert: /s/^ *severity: //p')
check "a stalled release is mailed as one that waits for a hand" \
  "$(receiver_of alertname=DeployStalled "severity=$urgent_severity" name=gateway)" urgent
check "the releases' conditions gone missing are mailed as an alert" "$(receiver_of alertname=DeployStateMissing)" alerts
check "a failed job of the window is mailed as an alert" "$(receiver_of alertname=DeployWindowJobFailed)" alerts
# The headers a receiver's mails carry besides the Subject.
headers_of() {
  sed -n "/^  - name: $1\$/,/^  - name: /p" "$copy/alertmanager/alertmanager.yml" | sed -n '/^ *headers:$/,/^ *html:/p' |
    grep -v '^ *#\|headers:\|Subject:\|html:' | sed 's/^ *//' | tr '\n' ' '
}
check "a mail that waits for a hand is marked as of the highest importance for the mail programs" \
  "$(headers_of urgent)" 'Importance: high X-Priority: "1" '
for receiver in alerts deploy-done deploy-failed; do
  check "a mail of $receiver is not marked as of the highest importance" "$(headers_of "$receiver")" ""
done

# What a receiver mails for a group of alerts, by its Subject or text in Alertmanager's config:
# mail <receiver> <Subject | text> <alert> ..., an alert being "<label>=<value>,...|<its message or summary>".
mail() {
  receiver="$1"
  part="$2"
  shift 2
  python3 -c '
import json, sys
alerts = []
for minute, alert in enumerate(sys.argv[1:]):
    labels, _, text = alert.partition("|")
    labels = dict(pair.split("=", 1) for pair in labels.split(","))
    alerts.append({"status": "firing", "labels": labels, "startsAt": "2026-11-26T10:%02d:00Z" % minute,
                   "annotations": {"message" if "kind" in labels else "summary": text}})
common = {key: value for key, value in alerts[0]["labels"].items() if all(a["labels"].get(key) == value for a in alerts)}
json.dump({"status": "firing", "alerts": alerts, "commonLabels": common}, sys.stdout)' "$@" >"$copy/alertmanager/data.json"
  written=$(sed -n "/^  - name: $receiver\$/,/^  - name: /p" "$copy/alertmanager/alertmanager.yml" |
    sed -n "s/^ *$part: '\(.*\)'\$/\1/p")
  amtool_run "$copy/alertmanager" template render --template.glob=mail.tmpl --template.data=data.json \
    --template.text="$written" 2>&1
}
# A Flux event of a release: event <reason> <release> <the commit of its chart>.
event() { echo "kind=HelmRelease,namespace=bank,name=$2,reason=$1,revision=0.1.0+$3.7|Helm ${1} said so"; }
NEW=abcdef123456
OLD=0123456789ab
check "the mail of a deploy's good end names the commit and OK" \
  "$(mail deploy-done Subject "$(event TestSucceeded control "$NEW")")" "deploy $NEW: OK"
check "the mail of a failed release names the commit that failed and the release" \
  "$(mail deploy-failed Subject "$(event UpgradeFailed gateway "$NEW")")" "deploy $NEW: FAILED at gateway"
check "the mail of a failed release that was rolled back says so, with the commit that failed" \
  "$(mail deploy-failed Subject "$(event UpgradeFailed gateway "$NEW")" "$(event RollbackSucceeded gateway "$OLD")")" \
  "deploy $NEW: ROLLED BACK at gateway"
check "the mail of a failed test that was rolled back says so, whichever event came first" \
  "$(mail deploy-failed Subject "$(event RollbackSucceeded gateway "$OLD")" "$(event TestFailed gateway "$NEW")")" \
  "deploy $NEW: ROLLED BACK at gateway"
check "the mail of a failed release whose rollback failed says so" \
  "$(mail deploy-failed Subject "$(event UpgradeFailed gateway "$NEW")" "$(event RollbackFailed gateway "$OLD")")" \
  "deploy $NEW: ROLLBACK FAILED at gateway"
check "the mail of a rollback without its failure names the commit as the one gone back to" \
  "$(mail deploy-failed Subject "$(event RollbackSucceeded gateway "$OLD")")" \
  "deploy: ROLLED BACK at gateway (rollback to $OLD)"
check "the mail of a failed rollback without its failure names the commit as the one to go back to" \
  "$(mail deploy-failed Subject "$(event RollbackFailed gateway "$OLD")")" \
  "deploy: ROLLBACK FAILED at gateway (rollback to $OLD)"
check "the mail of one alert names the alert" \
  "$(mail alerts Subject 'alertname=NodeMemoryLow|The node is low')" "alert: NodeMemoryLow"
check "the mail of an alert that holds for several names the alert and their number" \
  "$(mail alerts Subject 'alertname=ContainerMemoryNearLimit,container=ledger|Container ledger is near' \
    'alertname=ContainerMemoryNearLimit,container=gateway|Container gateway is near')" \
  "alert: ContainerMemoryNearLimit (2)"
check "the text of an alert's mail is the time and the summary of each" \
  "$(mail alerts text 'alertname=ContainerMemoryNearLimit,container=ledger|Container ledger is near' \
    'alertname=ContainerMemoryNearLimit,container=gateway|Container gateway is near')" \
  "2026-11-26 10:00:00 UTC  Container ledger is near
2026-11-26 10:01:00 UTC  Container gateway is near"
check "the text of a good end's mail is the event, with no word of a rollback" \
  "$(mail deploy-done text "$(event TestSucceeded control "$NEW")")" \
  "2026-11-26 10:00:00 UTC  TestSucceeded  control
Helm TestSucceeded said so"
check "the text of a failed test's mail says where the release check's line is, and that the rollback's mail follows" \
  "$(mail deploy-failed text "$(event TestFailed gateway "$NEW")")" \
  "2026-11-26 10:00:00 UTC  TestFailed  gateway
Helm TestFailed said so
The event does not carry the line of the release check; it is the last line of
  kubectl --namespace bank logs job/release-check-gateway

No rollback is reported yet: a mail follows when it ends. Without one the release is stalled and is handled by hand:
  kubectl --namespace bank get helmrelease gateway"
check "the text of a rolled back release's mail is both events, with no word of a rollback to come" \
  "$(mail deploy-failed text "$(event UpgradeFailed gateway "$NEW")" "$(event RollbackSucceeded gateway "$OLD")")" \
  "2026-11-26 10:00:00 UTC  UpgradeFailed  gateway
Helm UpgradeFailed said so

2026-11-26 10:01:00 UTC  RollbackSucceeded  gateway
Helm RollbackSucceeded said so"
stalled='alertname=DeployStalled,severity=critical,namespace=bank,name=gateway,reason=MissingRollbackTarget|Release gateway is stalled'
check "the subject of a stalled release's mail says that it needs a hand now, and where" \
  "$(mail urgent Subject "$stalled")" "NEEDS A HAND NOW: DeployStalled at gateway"
check "the text of a stalled release's mail gives the command that goes forward and the way back" \
  "$(mail urgent text "$stalled")" \
  "2026-11-26 10:00:00 UTC  Release gateway is stalled
Flux leaves the release as it is, and the releases after it wait. What it says:
  kubectl --namespace bank get helmrelease gateway
Forward, the pointer's version once more:
  at=\$(date +%s); kubectl --namespace bank annotate --overwrite helmrelease gateway reconcile.fluxcd.io/requestedAt=\$at reconcile.fluxcd.io/forceAt=\$at
Back: move the deploy pointer to the commit before. Not helm rollback: helm-controller refuses it."
check "the alert whose mail carries the release's commands is one of the rules" \
  "$(rules_of | grep -c "alert: $(sed -n 's/^.*eq .Labels.alertname "\([A-Za-z]*\)".*$/\1/p' "$original/files/alertmanager-mail.tmpl")\$")" 1
mailed | grep -o 'reason=~\?"[A-Za-z|]*"' | sed 's/^.*"\(.*\)"$/\1/' | tr '|' '\n' | sort -u >"$copy/alertmanager/routed"
named=$(grep -o 'reason "[A-Za-z]*"' "$original/files/alertmanager-mail.tmpl" | sed 's/^reason "\(.*\)"$/\1/' | sort -u)
check "the mail texts tell four reasons of Flux's events apart" "$(echo "$named" | wc -l)" 4
check "every reason the mail texts tell apart is one Alertmanager mails" \
  "$(echo "$named" | grep -vxFf "$copy/alertmanager/routed" || true)" ""
check "Prometheus and Alertmanager are named by digest" \
  "$(render | grep -cE '^ +image: prom/(prometheus|alertmanager):v[0-9.]+@sha256:[0-9a-f]{64}$')" 2
K3D="$original/../../k3d"
check "the k3d mail values mail to the cluster's mailbox" \
  "$(alertmanager -f "$K3D/mail-values.yaml" | grep -c 'smtp_smarthost: "mail-sink:1025"')" \
  "$(grep -c '^    - {name: smtp, port: 1025}$' "$K3D/mail-sink.yaml")"
check "the cluster's mailbox is named by digest" \
  "$(grep -cE '^ +image: axllent/mailpit:v[0-9.]+@sha256:[0-9a-f]{64}$' "$K3D/mail-sink.yaml")" 1
renders "the k3d values that show every alert at once" -f "$K3D/mail-values.yaml" -f "$K3D/alerts-now-values.yaml"
check "Alertmanager carries the chart's mail texts" \
  "$(alertmanager | grep -c 'mail.tmpl: .*define \\"deploy.failed.subject\\"')" 1
: >"$copy/ledger-lab/files/alertmanager-mail.tmpl"
cp "$original/files/release_check.py" "$original/files/create-topics.sh" "$copy/ledger-lab/files/"
CHART="$copy/ledger-lab"
refuses "empty mail texts" "files/alertmanager-mail.tmpl is empty or missing"
CHART="$original"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
