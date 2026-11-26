#!/bin/sh
# Checks release.sh, deployed-release.sh and window.sh by their output: every release waits for the one before it in
# the chain, rolls back on a failure and detects drift, a service is checked when its tag or the chart differs from the
# pointer deployed in full, that pointer is found by the version control reports, also when a tag or the version is
# digits alone, and the window's jobs open and close it with a memory limit and no right beyond reading and patching the
# pointer's Kustomization and listing Kustomizations, fail when the Kustomization is not left as they were to leave it,
# do not both start after a time without the node, and end, the opening one without waiting for a reconciliation,
# before the job of the other kind is due; and notifications.sh: every event of the releases, the good ends too, goes
# to the chart's Alertmanager, which takes the chain's last release for a deploy's end and keeps a failure until its
# rollback has ended; and install.sh, with a helm and a kubectl that only write down what they get: the chart's
# Prometheus may read the pods of Flux's namespace and nothing else there; and infra/k3d/flux.sh, in a repository of its
# own beside scripts and tools that only write down their calls: a commit's pointer is written by that commit's own
# scripts, and ONLY_IMAGES=1 moves nothing.
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
waits_for() {
  awk '/^kind: HelmRelease$/ { if (name != "") printf "%s<-%s ", name, on; name = ""; on = ""; release = 1 }
    release && name == "" && /^  name: / { name = $2 }
    /^  dependsOn: / { on = $0; sub(/^  dependsOn: /, "", on) }
    END { printf "%s<-%s ", name, on }' "$1"
}
check "every release waits for the one before it in the chain, the platform for none" "$(waits_for "$first")" \
  "platform<- reporting<-[{name: platform}] ledger<-[{name: reporting}] gateway<-[{name: ledger}] \
generator<-[{name: gateway}] control<-[{name: generator}] "
check "every release has the timeout above a setup Job's wait" "$(grep -c '^  timeout: 10m$' "$first")" 6
check "every release takes a new commit as a new chart" "$(grep -c '^      reconcileStrategy: Revision$' "$first")" 6
check "every release reads the cluster's own Secret of values" \
  "$(grep -A1 '^  valuesFrom:$' "$first" | grep -c '^    - {kind: Secret, name: ledger-lab-values, optional: true}$')" 6
check "every service's tag is written as a string" "$(grep -cE '^    services: \{[a-z]+: \{tag: "[0-9a-f]{12}"\}\}$' "$first")" 5
check "the chart's commit is written as a string" "$(grep -cE '^  ref: \{commit: "[0-9a-f]{40}"\}$' "$first")" 1
check "every release's version is written as a string" "$(grep -cE '^    version: "[^"]+"$' "$first")" 6
check "without a pointer before, no service is checked" "$(checked "$first")" ""

same="$work/same.yaml"
PREVIOUS_RELEASE="$first" "$HERE/release.sh" HEAD "$URL" >"$same"
check "a service that keeps its tag is not checked" "$(checked "$same")" ""

before="$work/before.yaml"
sed 's/^    services: {gateway: {tag: "[0-9a-f]*"}}$/    services: {gateway: {tag: "000000000000"}}/' "$first" >"$before"
changed="$work/changed.yaml"
PREVIOUS_RELEASE="$before" "$HERE/release.sh" HEAD "$URL" >"$changed"
check "only the service whose tag changed is checked" "$(checked "$changed")" "gateway "

sed '/^    services: {ledger: /d' "$first" >"$before"
PREVIOUS_RELEASE="$before" "$HERE/release.sh" HEAD "$URL" >"$changed"
check "a service the pointer before did not hold is not checked" "$(checked "$changed")" ""

# Digits alone, and digits around an e: both a number to YAML when unquoted.
for digits in 123456789012 012345670123 12345e678901; do
  sed "s/^    services: {gateway: {tag: \"[0-9a-f]*\"}}\$/    services: {gateway: {tag: \"$digits\"}}/" "$first" >"$before"
  PREVIOUS_RELEASE="$before" "$HERE/release.sh" HEAD "$URL" >"$changed"
  check "a tag of digits alone ($digits) in the deployed pointer is read as the tag it is" "$(checked "$changed")" "gateway "
done

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
  "$(grep '^  ref: ' "$deployed")" "  ref: {commit: \"$base\"}"
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

# A commit without a tag before it has its abbreviated hash as its version; one of digits alone is found by trying.
DIGITS_TRIES=5000
numbered=""
try=0
while [ -z "$numbered" ] && [ "$try" -lt "$DIGITS_TRIES" ]; do
  try=$((try + 1))
  candidate=$(GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@localhost GIT_COMMITTER_NAME=test \
    GIT_COMMITTER_EMAIL=test@localhost git commit-tree "$fixed^{tree}" -p "$fixed" -m "try $try")
  case "$(version_of "$candidate")" in *[!0-9]*) ;; *) numbered="$candidate" ;; esac
done
if [ -z "$numbered" ]; then
  check "a commit whose version is digits alone" none found
else
  "$HERE/release.sh" "$numbered" "$URL" >"$work/numbered.yaml"
  check "a version of digits alone is written as a string" \
    "$(grep -c "^    version: \"$(version_of "$numbered")\"\$" "$work/numbered.yaml")" 6
  point "$work/numbered.yaml"
  check "the deployed pointer is found by a version of digits alone" \
    "$("$HERE/deployed-release.sh" "$(version_of "$numbered")" | grep '^  ref: ')" "  ref: {commit: \"$numbered\"}"
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
sed 's/^  ref: {commit: "[0-9a-f]*"}$/  ref: {commit: "0000000000000000000000000000000000000000"}/' "$work/charted.yaml" \
  >"$work/unknown.yaml"
PREVIOUS_RELEASE="$work/unknown.yaml" "$HERE/release.sh" "$elsewhere" "$URL" >"$work/blind.yaml" 2>/dev/null
check "a deployed pointer whose commit is not here has every service checked" "$(checked "$work/blind.yaml")" \
  "$ALL_SERVICES"
cd "$HERE/../.."

window="$work/window.yaml"
"$HERE/window.sh" >"$window"
schedule_of() { object_of "$1" "$2" | grep -E '^  schedule:|^ +flux |^ +kubectl .* patch ' | sed 's/^ *//' | tr '\n' ' '; }
check "the window opens at ten past every hour by removing the pointer's pause" \
  "$(schedule_of "$window" deploy-window-open)" \
  'schedule: "10 * * * *" kubectl --namespace flux-system patch kustomization deploy --type merge \ '
check "the window closes at twenty past every hour by suspending the pointer" \
  "$(schedule_of "$window" deploy-window-close)" \
  'schedule: "20 * * * *" flux suspend kustomization deploy --namespace flux-system '

minute_of() { object_of "$window" "$1" | sed -n 's/^  schedule: "\([0-9]*\) .*/\1/p'; }
deadline_of() { object_of "$window" "$1" | sed -n 's/^  startingDeadlineSeconds: //p'; }
life_of() { object_of "$window" "$1" | sed -n 's/^      activeDeadlineSeconds: //p'; }
HOUR_SECONDS=3600
window_seconds=$((($(minute_of deploy-window-close) - $(minute_of deploy-window-open)) * 60))
for job in deploy-window-open deploy-window-close; do
  deadline=$(deadline_of "$job")
  if [ "${deadline:-$window_seconds}" -lt "$window_seconds" ]; then within=shorter; else within="${deadline:-none}"; fi
  check "$job skips a run missed for longer than a time shorter than the window, so both never start at once" \
    "$within" shorter
  life=$(life_of "$job")
  latest=$((${deadline:-$HOUR_SECONDS} + ${life:-$HOUR_SECONDS}))
  if [ "$latest" -lt "$window_seconds" ]; then within=shorter; else within="$latest of $window_seconds"; fi
  check "$job, started late and retried, has ended before the window's length has passed" "$within" shorter
  closed_seconds=$((HOUR_SECONDS - window_seconds))
  if [ "$latest" -lt "$closed_seconds" ]; then within=shorter; else within="$latest of $closed_seconds"; fi
  check "$job, started late and retried, has ended before the time the window stays closed has passed" "$within" shorter
done
check "both jobs keep their retries within the job's life" \
  "$(grep -A1 '^      backoffLimit: 2$' "$window" | grep -c '^      activeDeadlineSeconds: [0-9]*$')" 2

# A job's script, run with a flux that changes nothing and a kubectl that answers with the state given.
tools="$work/tools"
mkdir -p "$tools"
cat >"$tools/flux" <<'EOF'
#!/bin/sh
echo "$@" >>"$CALLS"
EOF
cat >"$tools/kubectl" <<'EOF'
#!/bin/sh
echo "$@" >>"$CALLS"
case " $* " in *" get "*) ;; *) exit 0 ;; esac
printf '%s' "$SUSPEND_STATE"
exit "$KUBECTL_EXIT"
EOF
chmod +x "$tools/flux" "$tools/kubectl"
script_of() {
  object_of "$window" "$1" | awk '/^ +- \|$/ { on = 1; next } /^ +securityContext:$/ { on = 0 } on' | sed 's/^ *//'
}
job_ends() {
  if CALLS="$work/calls" SUSPEND_STATE="$2" KUBECTL_EXIT="${3:-0}" PATH="$tools:$PATH" \
      sh -euc "$(script_of "$1")" 2>/dev/null; then
    echo ended
  else
    echo failed
  fi
}
check "the opening job does not wait for a reconciliation: it runs no flux, whose resume waits" \
  "$(script_of deploy-window-open | grep -cE '(^| )flux |wait' || true)" 0
check "the closing job ends well when the pointer is suspended" "$(job_ends deploy-window-close true)" ended
check "the closing job fails when the pointer is not suspended after flux suspend" \
  "$(job_ends deploy-window-close false)" failed
check "the closing job fails when the pointer has no suspend after flux suspend" "$(job_ends deploy-window-close '')" failed
check "the opening job ends well when the pointer is resumed" "$(job_ends deploy-window-open false)" ended
check "the opening job ends well when the resumed pointer has no suspend at all" "$(job_ends deploy-window-open '')" ended
check "the opening job fails when the pointer is still suspended after the patch" \
  "$(job_ends deploy-window-open true)" failed
check "a job that cannot read the pointer's state fails" "$(job_ends deploy-window-open false 1)" failed
check "the jobs change the pause, then read the state, of the pointer alone" \
  "$(sort -u "$work/calls" | tr '\n' ';')" \
  "--namespace flux-system get kustomization deploy -o jsonpath={.spec.suspend};\
--namespace flux-system patch kustomization deploy --type merge --patch {\"spec\":{\"suspend\":false}};\
suspend kustomization deploy --namespace flux-system;"
check "both jobs run their script with a shell that stops at the first failure" \
  "$(grep -c '^ *command: \[sh, -euc\]$' "$window")" 2
check "both jobs keep UTC" "$(grep -c '^  timeZone: Etc/UTC$' "$window")" 2
check "both jobs have a memory limit" "$(grep -c '^ *limits: {memory: 64Mi}$' "$window")" 2
rules_of() { awk '/^rules:$/ { on = 1; next } /^---$/ { on = 0 } on && !/^ *#/' "$1" | sed 's/^ *//' | tr '\n' ' '; }
check "the jobs may read and patch the pointer's Kustomization, list Kustomizations and nothing else" \
  "$(rules_of "$window")" \
  "- apiGroups: [kustomize.toolkit.fluxcd.io] resources: [kustomizations] resourceNames: [deploy] verbs: [get, patch] \
- apiGroups: [kustomize.toolkit.fluxcd.io] resources: [kustomizations] verbs: [list] "
check "the jobs may list Kustomizations, which the CLI does before it suspends" \
  "$(grep -c '^    verbs: \[list\]$' "$window")" 1
check "the deadlines can be moved with the schedules" \
  "$(WINDOW_START_DEADLINE_SECONDS=30 WINDOW_ACTIVE_DEADLINE_SECONDS=60 "$HERE/window.sh" |
    grep -E '^ +(starting|active)DeadlineSeconds:' | sed 's/^ *//' | sort | uniq -c | tr -s ' \n' ' ')" \
  ' 2 activeDeadlineSeconds: 60 2 startingDeadlineSeconds: 30 '
check "the schedules can be moved" \
  "$(WINDOW_OPENS='*/2 * * * *' WINDOW_CLOSES='1-59/2 * * * *' "$HERE/window.sh" | grep -E '^  schedule:' | tr '\n' ' ')" \
  '  schedule: "*/2 * * * *"   schedule: "1-59/2 * * * *" '

notifications="$work/notifications.yaml"
"$HERE/notifications.sh" >"$notifications"
CHART_VALUES="$HERE/../helm/ledger-lab/values.yaml"
# A value of the chart's alertmanager block, by its key.
alertmanager_value() { sed -n '/^alertmanager:$/,/^[a-z]/p' "$CHART_VALUES" | sed -n "s/^ *$1: //p"; }
check "what Flux reports goes to the chart's Alertmanager, in the bank's namespace, on the chart's port" \
  "$(object_of "$notifications" deploy-mail | grep -E '^  (type|address):' | sed 's/^ *//' | tr '\n' ' ')" \
  "type: alertmanager address: http://alertmanager.ledger-lab:$(alertmanager_value port)/api/v2/alerts/ "
sed 's/^  port: [0-9]*$/  port: 9999/' "$CHART_VALUES" >"$work/values.yaml"
check "the port is the one of the chart's values, whatever it is" \
  "$(CHART_VALUES="$work/values.yaml" "$HERE/notifications.sh" | sed -n 's/^  address: //p')" \
  "http://alertmanager.ledger-lab:9999/api/v2/alerts/"
status=0
CHART_VALUES=/dev/null "$HERE/notifications.sh" >/dev/null 2>&1 || status=$?
check "values without Alertmanager's port give no manifest" "$status" 1
check "the Provider and the Alert follow the namespace of the bank" \
  "$(NAMESPACE=bank "$HERE/notifications.sh" | grep -c -e '^  namespace: bank$' -e 'http://alertmanager.bank:')" 3
check "the Alert reports the good end of a release too, not only its errors" \
  "$(grep -c '^  eventSeverity: info$' "$notifications")" 1
check "the Alert reports every release" "$(grep -A1 '^  eventSources:$' "$notifications" | tail -1)" \
  '    - {kind: HelmRelease, name: "*"}'
check "the Alert sends through the Provider" "$(grep -c '^  providerRef: {name: deploy-mail}$' "$notifications")" 1
check "install.sh applies the notifications" \
  "$(grep -cF 'notifications.sh" | kubectl apply -f -' "$HERE/install.sh")" 1

installer="$work/installer"
applied="$work/applied.yaml"
mkdir -p "$installer"
cat >"$installer/kubectl" <<'EOF_KUBECTL'
#!/bin/sh
case "$*" in
*"apply -f -"*)
  cat >>"$APPLIED"
  echo --- >>"$APPLIED"
  ;;
esac
EOF_KUBECTL
helm_calls="$work/helm-calls"
# shellcheck disable=SC2016
printf '#!/bin/sh\necho "$*" >>"$HELM_CALLS"\n' >"$installer/helm"
chmod +x "$installer/kubectl" "$installer/helm"
PATH="$installer:$PATH" APPLIED="$applied" HELM_CALLS="$helm_calls" NAMESPACE=bank "$HERE/install.sh" "$URL" >/dev/null
flux_timeout() { sed -n 's/^upgrade --install flux .* --wait --timeout \([^ ]*\)$/\1/p' "$helm_calls" | tail -1; }
check "install.sh waits 5 minutes for Flux's pods" "$(flux_timeout)" 5m
PATH="$installer:$PATH" APPLIED="$work/applied-again.yaml" HELM_CALLS="$helm_calls" FLUX_INSTALL_TIMEOUT=15m \
  "$HERE/install.sh" "$URL" >/dev/null
check "a node without Flux's images is given the time the caller names" "$(flux_timeout)" 15m
# The object of a kind and a name in what install.sh applied, on one line.
applied_of() {
  awk -v kind="$1" -v name="$2" '/^---$/ { if (on && named) print line; on = 0; named = 0; line = ""; next }
    $0 == "kind: " kind { on = 1 } $0 == "  name: " name { named = 1 }
    /^#/ { next } { sub(/^ +/, ""); line = line $0 " " }' "$applied"
}
flux_namespace=$(sed -n '/^flux:$/,/^$/s/^  namespace: //p' "$CHART_VALUES")
check "install.sh lets the chart's Prometheus read the pods of Flux's namespace, where the chart looks for them" \
  "$(applied_of Role prometheus-pods)" \
  "apiVersion: rbac.authorization.k8s.io/v1 kind: Role metadata: name: prometheus-pods namespace: $flux_namespace \
rules: - apiGroups: [\"\"] resources: [\"pods\"] verbs: [\"get\", \"list\", \"watch\"] "
check "the right is given to the ServiceAccount of Prometheus in the bank's namespace" \
  "$(applied_of RoleBinding prometheus-pods)" \
  "apiVersion: rbac.authorization.k8s.io/v1 kind: RoleBinding metadata: name: prometheus-pods namespace: $flux_namespace \
roleRef: apiGroup: rbac.authorization.k8s.io kind: Role name: prometheus-pods \
subjects: - kind: ServiceAccount name: prometheus namespace: bank "
check "the ServiceAccount is the one the chart's Prometheus runs as" \
  "$(grep -c '^      serviceAccountName: prometheus$' "$HERE/../helm/ledger-lab/templates/prometheus.yaml")" 1
check "install.sh gives no right over the whole cluster" "$(grep -c '^kind: ClusterRole' "$applied" || true)" 0
check "the chart mails a deploy's good end at the last release of the chain" "$(alertmanager_value lastRelease)" \
  "$(grep '^kind: HelmRelease$' -A2 "$first" | sed -n 's/^  name: //p' | tail -1)"
minutes_of() { echo "${1%m}"; }
timeout_minutes=$(minutes_of "$(sed -n 's/^  timeout: //p' "$first" | sort -u)")
lifetime_minutes=$(minutes_of "$(alertmanager_value eventLifetime)")
if [ "$lifetime_minutes" -gt "$timeout_minutes" ]; then outlives=longer; else outlives="$lifetime_minutes of $timeout_minutes"; fi
check "a failure's event is kept longer than the timeout of a release, within which the rollback ends" "$outlives" longer

k3d_repo="$work/k3d"
k3d_calls="$work/k3d-calls"
k3d_bare="$k3d_repo/build/flux-git/ledger-lab.git"
mkdir -p "$k3d_repo/infra/k3d" "$k3d_repo/infra/flux" "$k3d_repo/infra/ci" "$k3d_repo/bin"
recorder() {
  # shellcheck disable=SC2016
  printf '#!/bin/sh\necho "%s" >>"$CALLS"\n' "$1" >"$2"
  chmod +x "$2"
}
cp "$HERE/../k3d/flux.sh" "$k3d_repo/infra/k3d/flux.sh"
recorder cluster "$k3d_repo/infra/k3d/cluster.sh"
recorder images "$k3d_repo/infra/k3d/images.sh"
recorder install "$k3d_repo/infra/flux/install.sh"
: >"$k3d_repo/infra/k3d/git-http.py"
# No release ledger-lab of infra/k3d/up.sh, a network with a gateway, and nothing else answers.
printf '#!/bin/sh\nexit 1\n' >"$k3d_repo/bin/helm"
printf '#!/bin/sh\necho 192.0.2.1\n' >"$k3d_repo/bin/docker"
printf '#!/bin/sh\nexit 0\n' >"$k3d_repo/bin/kubectl"
chmod +x "$k3d_repo/bin/helm" "$k3d_repo/bin/docker" "$k3d_repo/bin/kubectl"
# A commit's rule for its tags and its pointer, which names the rule that wrote it.
rules_of_commit() {
  printf '#!/bin/sh\necho "%s"\n' "$1-tag" >"$k3d_repo/infra/ci/module-tag.sh"
  # shellcheck disable=SC2016
  printf '#!/bin/sh\necho "pointer by %s for $1, tagged $("$(dirname "$0")/../ci/module-tag.sh")"\n' "$1" \
    >"$k3d_repo/infra/flux/release.sh"
  printf '#!/bin/sh\n' >"$k3d_repo/infra/ci/changed.sh"
  chmod +x "$k3d_repo/infra/ci/module-tag.sh" "$k3d_repo/infra/flux/release.sh" "$k3d_repo/infra/ci/changed.sh"
}
k3d_git() { git -C "$k3d_repo" -c user.name=test -c user.email=test@example.invalid "$@"; }
k3d_git init -q -b main
printf 'build/\n' >"$k3d_repo/.gitignore"
k3d_git add .gitignore infra/k3d
k3d_git commit -q -m "from before Flux"
before_flux=$(k3d_git rev-parse HEAD)
rules_of_commit base
k3d_git add -A
k3d_git commit -q -m base
k3d_base=$(k3d_git rev-parse HEAD)
rules_of_commit tree
k3d_git add -A
k3d_git commit -q -m tree
flux_sh() { (cd "$work" && PATH="$k3d_repo/bin:$PATH" CALLS="$k3d_calls" "$k3d_repo/infra/k3d/flux.sh" "$@" 2>&1); }
k3d_pointer() { git -C "$k3d_bare" show deploy:release.yaml 2>/dev/null || echo "no pointer"; }

ONLY_IMAGES=1 flux_sh "$k3d_base" >/dev/null
check "ONLY_IMAGES=1 puts the commit's images into the node and neither writes a pointer nor touches Flux" \
  "$(tr '\n' ' ' <"$k3d_calls"), $(k3d_pointer)" "cluster images , no pointer"
: >"$k3d_calls"
flux_sh "$k3d_base" >/dev/null
check "without it the pointer is written and Flux told of it" "$(tr '\n' ' ' <"$k3d_calls")" "cluster images install "
check "a commit's pointer is written by that commit's own release.sh and module-tag.sh, not by the working tree's" \
  "$(k3d_pointer)" "pointer by base for $k3d_base, tagged base-tag"
status=0
refused=$(flux_sh "$before_flux") || status=$?
check "a commit from before the pointer's scripts is refused, and the pointer stays" \
  "$status, $(echo "$refused" | grep -c 'lacks infra/ci/changed.sh'), $(k3d_pointer)" \
  "2, 1, pointer by base for $k3d_base, tagged base-tag"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
