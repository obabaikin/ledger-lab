#!/bin/sh
# Checks move-pointer.sh on a scratch repository and its clone, with a curl that answers as the site and a gh that
# answers as GitHub's list of e2e runs: the pointer stays while the site runs another version or does not answer and
# while the next commit's last completed e2e run did not pass; a commit with nothing to deploy is passed; a passed e2e
# gives one commit on deploy, to the next commit of main and no further, a merge counting as one commit; a commit the
# pointer was at before is left to the operator; a commit's pointer is written by that commit's own scripts; and the
# workflow starts the script by the hour and by hand only.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/move-pointer.sh"
RELEASE="$HERE/../flux/release.sh"
WORKFLOW="$HERE/../../.github/workflows/deploy-pointer.yml"
# The scripts a commit decides its own pointer with.
OWN_SCRIPTS="infra/ci/changed.sh infra/ci/module-tag.sh infra/flux/release.sh"
URL=http://git.example.invalid/ledger-lab.git
SITE=http://site.example.invalid
failures=0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

check() {
  if [ "$2" = "$3" ]; then
    echo "ok: $1"
  else
    echo "FAIL: $1: got '$2', expected '$3'"
    failures=$((failures + 1))
  fi
}

tools="$work/tools"
runs="$work/runs"
calls="$work/calls"
mkdir -p "$tools" "$runs"
cat >"$tools/curl" <<'EOF'
#!/bin/sh
echo "curl $*" >>"$CALLS"
[ -n "${SITE_ANSWER:-}" ] || exit 22
printf '%s' "$SITE_ANSWER"
EOF
# Answers with the runs written for the commit asked about; MEANWHILE is what happens while the script asks.
cat >"$tools/gh" <<'EOF'
#!/bin/sh
echo "gh $*" >>"$CALLS"
[ -z "${MEANWHILE:-}" ] || sh -c "$MEANWHILE"
[ -z "${GH_FAILS:-}" ] || exit 1
for argument in "$@"; do
  case "$argument" in head_sha=*) commit="${argument#head_sha=}" ;; esac
done
if [ -f "$RUNS/$commit" ]; then
  cat "$RUNS/$commit"
else
  echo '{"total_count": 0, "workflow_runs": []}'
fi
EOF
chmod +x "$tools/curl" "$tools/gh"

# The completed e2e runs of a commit, as GitHub lists them, in the order given:
# e2e_runs <commit> <conclusion>@<started>[@<created>] ...; a run that was not rerun was created when it started.
e2e_runs() {
  commit="$1"
  shift
  list=""
  for run in "$@"; do
    times="${run#*@}"
    list="$list${list:+, }{\"head_sha\": \"$commit\", \"status\": \"completed\", \"conclusion\": \"${run%%@*}\", \"run_started_at\": \"${times%%@*}\", \"created_at\": \"${times##*@}\"}"
  done
  echo "{\"total_count\": $#, \"workflow_runs\": [$list]}" >"$runs/$commit"
}
passed() { e2e_runs "$1" success@2026-11-26T10:00:00Z; }

bank="$work/bank"
clone="$work/clone"
git init -q -b main "$bank"
cd "$bank"
git config commit.gpgsign false
commit_change() {
  for path in "$@"; do
    mkdir -p "$(dirname "$path")"
    echo "$path" >>"$path"
  done
  git add -A
  git commit -q -m "$*"
  git rev-parse HEAD
}
# A pointer made by hand, as the first one is: point <commit> [<the pointer deployed in full>].
point() {
  PREVIOUS_RELEASE="${2:-}" "$RELEASE" "$1" "$URL" >"$work/pointed.yaml"
  blob=$(git hash-object -w "$work/pointed.yaml")
  tree=$(printf '100644 blob %s\trelease.yaml\n' "$blob" | git mktree)
  parent=$(git rev-parse --verify --quiet refs/heads/deploy || true)
  git update-ref refs/heads/deploy "$(git commit-tree "$tree" ${parent:+-p "$parent"} -m "by hand $1")"
}
version_of() { git describe --tags --match 'v[0-9]*' --long --always "$1"; }
tip() { git rev-parse --verify --quiet refs/heads/deploy || echo none; }
pointed() { git show refs/heads/deploy:release.yaml | sed -n 's/^  ref: {commit: "\(.*\)"}$/\1/p'; }
# Runs the script in the clone, the site answering with a version; the rest of the line is the environment of the run.
# Leaves the exit code in $status and the output in $work/output.
move() {
  site_version="$1"
  shift
  status=0
  : >"$calls"
  (cd "$clone" && env PATH="$tools:$PATH" CALLS="$calls" RUNS="$runs" SITE_URL="$SITE" REPOSITORY_URL="$URL" \
    GITHUB_REPOSITORY=owner/bank SITE_ANSWER="{\"turnstileSiteKey\": null, \"version\": \"$site_version\"}" "$@" \
    "$SCRIPT") >"$work/output" 2>&1 || status=$?
}
stays() {
  check "$1" "$status $(tip)" "0 $before"
}

for script in $OWN_SCRIPTS; do
  mkdir -p "$(dirname "$script")"
  cp "$HERE/../../$script" "$script"
done
base=$(commit_change reporting/build.gradle.kts ledger-service/build.gradle.kts gateway/build.gradle.kts \
  generator/build.gradle.kts control/build.gradle.kts infra/helm/ledger-lab/values.yaml)
git clone -q "$bank" "$clone"
# After the clone: the script has to bring the tags itself, the versions are counted from them.
git tag v1.0.0 "$base"
docs=$(commit_change README.md)
gateway=$(commit_change gateway/First.java)
both=$(commit_change gateway/Second.java generator/Second.java)

passed "$gateway"
move "$(version_of "$base")"
check "without a deploy branch nothing is written and the run ends well" "$status $(tip)" "0 none"

point "$base"
git show refs/heads/deploy:release.yaml >"$work/base.yaml"
before=$(tip)

move v0.9.0-3-g0000000
stays "a site that runs another version than the pointer's leaves the pointer where it is"
check "and no e2e run is asked about then" "$(grep -c '^gh ' "$calls" || true)" 0
move "$(version_of "$base")" SITE_ANSWER=
stays "a site that does not answer leaves the pointer where it is"
move "$(version_of "$base")" SITE_ANSWER='{"turnstileSiteKey": null}'
stays "a site that answers without a version leaves the pointer where it is"

e2e_runs "$gateway" failure@2026-11-26T10:00:00Z
passed "$both"
move "$(version_of "$base")"
stays "a failed e2e run of the next commit leaves the pointer where it is, whatever the commits after it passed"
rm "$runs/$gateway"
move "$(version_of "$base")"
stays "a next commit without a completed e2e run leaves the pointer where it is"
e2e_runs "$gateway" success@2026-11-26T10:00:00Z failure@2026-11-26T12:00:00Z cancelled@2026-11-26T11:00:00Z
move "$(version_of "$base")"
stays "a next commit whose last completed e2e run failed after one that passed leaves the pointer where it is"
e2e_runs "$gateway" failure@2026-11-26T12:00:00Z success@2026-11-26T10:00:00Z
move "$(version_of "$base")"
stays "a failed run that started last leaves the pointer where it is, though GitHub lists a passed one after it"
e2e_runs "$gateway" success@2026-11-26T10:00:00Z failure@2026-11-26T13:00:00Z@2026-11-26T09:00:00Z
move "$(version_of "$base")"
stays "a run created first and rerun last counts as the last one: its failure leaves the pointer where it is"
passed "$gateway"
move "$(version_of "$base")" GH_FAILS=1
check "an answer GitHub does not give about the e2e runs fails the run and leaves the pointer" \
  "$([ "$status" -ne 0 ] && echo failed || echo ended) $(tip)" "failed $before"

operator=$(git commit-tree "$before^{tree}" -p "$before" -m "by hand, meanwhile")
move "$(version_of "$base")" MEANWHILE="git -C '$bank' update-ref refs/heads/deploy $operator"
check "a pointer moved by hand while the run was deciding is kept and the run fails" \
  "$([ "$status" -ne 0 ] && echo failed || echo ended) $(tip)" "failed $operator"
git update-ref refs/heads/deploy "$before"

e2e_runs "$gateway" failure@2026-11-26T10:00:00Z success@2026-11-26T12:00:00Z
main_before=$(git rev-parse refs/heads/main)
move "$(version_of "$base")"
check "a next commit whose last completed e2e run passed gives one commit on deploy, on top of the pointer before" \
  "$status $(git rev-list --count "$before..refs/heads/deploy") $(git rev-parse refs/heads/deploy^)" "0 1 $before"
check "the pointer passes a commit with nothing to deploy and goes to the next one of main, not to its newest" \
  "$(pointed)" "$gateway"
check "a commit with nothing to deploy is passed without asking about its e2e run" "$(grep -c "$docs" "$calls" || true)" 0
check "the e2e runs asked about are the completed ones of the e2e workflow for that commit" \
  "$(grep '^gh ' "$calls")" \
  "gh api --method GET repos/owner/bank/actions/workflows/e2e.yml/runs -f head_sha=$gateway -f status=completed -F per_page=100"
check "the site is asked for its version at /api/site" "$(grep -c "^curl .* $SITE/api/site\$" "$calls")" 1
PREVIOUS_RELEASE="$work/base.yaml" "$RELEASE" "$gateway" "$URL" >"$work/expected.yaml"
check "the pointer is the commit's release.yaml, its checks decided against the pointer deployed in full" \
  "$(git show refs/heads/deploy:release.yaml | cmp -s - "$work/expected.yaml" && echo same || echo differs)" same
check "so the service whose tag changed gets the release check" \
  "$(grep -c '^    releaseCheck: {enabled: true}$' "$work/expected.yaml")" 1
check "the pointer's commit holds release.yaml alone" "$(git ls-tree -r --name-only refs/heads/deploy)" release.yaml
check "the run writes the deploy branch alone" \
  "$(git rev-parse refs/heads/main) $(git for-each-ref --format='%(refname)' | tr '\n' ' ')" \
  "$main_before refs/heads/deploy refs/heads/main refs/tags/v1.0.0 "

before=$(tip)
move "$(version_of "$base")"
stays "until the site runs the new pointer's version, the next commit waits, its e2e passed or not"
git show refs/heads/deploy:release.yaml >"$work/gateway.yaml"
move "$(version_of "$gateway")"
check "once the site runs the pointer's version, the next commit that passed its e2e gets the pointer" \
  "$status $(pointed) $(git rev-parse refs/heads/deploy^)" "0 $both $before"

before=$(tip)
move "$(version_of "$both")"
stays "with no commit of main after the pointer's, the pointer stays"
later=$(commit_change README.md docs/notes.md)
passed "$later"
move "$(version_of "$both")"
stays "with nothing to deploy in the commits after the pointer's, the pointer stays and the run ends well"

git checkout -q -b feature
first_of_branch=$(commit_change control/Branch.java)
commit_change control/Branch.java >/dev/null
git checkout -q main
git merge -q --no-ff -m merge feature
merge=$(git rev-parse HEAD)
passed "$first_of_branch"
passed "$merge"
move "$(version_of "$both")"
check "a merge is taken as the one next commit of main, not the commits of its branch" "$status $(pointed)" "0 $merge"

git show refs/heads/deploy:release.yaml >"$work/merge.yaml"
point "$both" "$work/merge.yaml"
before=$(tip)
move "$(version_of "$both")"
stays "a pointer moved back by hand stays: the commit it was at before is not deployed again, its e2e passed or not"
check "the run says that the operator moves such a pointer forward" \
  "$(grep -c "the pointer was at $merge before and was moved back by hand: the operator moves it forward" "$work/output")" 1
check "and no e2e run is asked about for a commit the pointer was at before" "$(grep -c '^gh ' "$calls" || true)" 0
point "$merge" "$work/merge.yaml"
forward=$(tip)
# This commit's module-tag.sh and release.sh differ from the ones the run is started from, and the commit after it
# changes them again.
sed -i 's/^TAG_LENGTH=12$/TAG_LENGTH=10/' infra/ci/module-tag.sh
sed -i 's/^RELEASE_TIMEOUT=10m$/RELEASE_TIMEOUT=11m/' infra/flux/release.sh
git add -A
git commit -q -m 'tags of 10 characters, a longer timeout'
own_rule=$(git rev-parse HEAD)
sed -i 's/^TAG_LENGTH=10$/TAG_LENGTH=8/' infra/ci/module-tag.sh
sed -i 's/^RELEASE_TIMEOUT=11m$/RELEASE_TIMEOUT=12m/' infra/flux/release.sh
git add -A
git commit -q -m 'tags of 8 characters, a still longer timeout'
later_rule=$(git rev-parse HEAD)
passed "$own_rule"
passed "$later_rule"
move "$(version_of "$merge")"
check "once the operator has moved the pointer forward by hand, the next commit of main gets the pointer again" \
  "$status $(pointed) $(git rev-parse refs/heads/deploy^)" "0 $own_rule $forward"
check "a commit's pointer is written by its own release.sh, not the one the run is started from or a later commit's" \
  "$(git show refs/heads/deploy:release.yaml | grep -c '^  timeout: 11m$')" 6
check "a commit's pointer names its images by its own module-tag.sh" \
  "$(git show refs/heads/deploy:release.yaml | grep -cE '^    services: \{[a-z]+: \{tag: "[0-9a-f]{10}"\}\}$')" 5
move "$(version_of "$own_rule")"
check "and the commit after it gets its pointer by its own scripts in turn" \
  "$(pointed) $(git show refs/heads/deploy:release.yaml | grep -c '^  timeout: 12m$')" "$later_rule 6"
before=$(tip)
git rm -q infra/ci/changed.sh
git commit -q -m 'no changed.sh'
move "$(version_of "$later_rule")"
check "a commit without the scripts that decide its pointer fails the run and leaves the pointer" \
  "$([ "$status" -ne 0 ] && echo failed || echo ended) $(tip)" "failed $before"

before=$(tip)
point "$first_of_branch" "$work/gateway.yaml"
by_hand=$(tip)
after=$(commit_change gateway/Third.java)
passed "$after"
move "$(version_of "$first_of_branch")"
check "a pointer set by hand to a commit that is not one of main's own fails the run and stays" \
  "$([ "$status" -ne 0 ] && echo failed || echo ended) $(tip)" "failed $by_hand"

status=0
(cd "$clone" && env PATH="$tools:$PATH" CALLS="$calls" REPOSITORY_URL="$URL" GITHUB_REPOSITORY=owner/bank "$SCRIPT") \
  >/dev/null 2>&1 || status=$?
check "a call without the site's address is refused" "$status $(tip)" "2 $by_hand"

check "the workflow starts a run by the hour and by hand only, not by a finished e2e" \
  "$(sed -n '/^on:$/,/^$/p' "$WORKFLOW" | grep -E '^  [a-z_]+:' | tr -d ' \n')" "schedule:workflow_dispatch:"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
