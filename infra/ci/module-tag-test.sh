#!/bin/sh
# Checks module-tag.sh on a scratch repository: a module's tag follows its own files and common, and nothing else.
set -eu

SCRIPT="$(cd "$(dirname "$0")" && pwd)/module-tag.sh"
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q
git config user.email test@example.invalid
git config user.name test

mkdir -p common gateway control infra/docker gradle
echo 'implementation(project(":common"))' >gateway/build.gradle.kts
echo 'plugins { java }' >control/build.gradle.kts
echo 'class Message' >common/Message.java
echo 'class Gateway' >gateway/Gateway.java
echo 'class Control' >control/Control.java
echo 'FROM jre' >infra/docker/Dockerfile
echo 'include("common", "gateway", "control")' >settings.gradle.kts
echo '' >build.gradle.kts
echo 'spring = "4"' >gradle/libs.versions.toml
echo 'notes' >README.md
git add -A && git commit -qm start

failures=0
check() {
  if [ "$2" = "$3" ]; then
    echo "ok: $1"
  else
    echo "FAILED: $1 ($2 vs $3)"
    failures=$((failures + 1))
  fi
}
check_not() {
  if [ "$2" != "$3" ]; then
    echo "ok: $1"
  else
    echo "FAILED: $1 (both $2)"
    failures=$((failures + 1))
  fi
}
commit() {
  echo "$2" >>"$1"
  git commit -qam "$1"
}

gateway=$("$SCRIPT" gateway)
control=$("$SCRIPT" control)
first_control="$control"
check "the tag is twelve characters" "$(printf %s "$gateway" | wc -c | tr -d ' ')" 12
check "the working tree hashes as its commit when nothing changed" "$("$SCRIPT" gateway --worktree)" "$gateway"

echo 'class Gateway2' >>gateway/Gateway.java
check_not "an uncommitted change shows in the working tree's tag" "$("$SCRIPT" gateway --worktree)" "$gateway"
check "an uncommitted change does not show in HEAD's tag" "$("$SCRIPT" gateway)" "$gateway"
git checkout -q -- gateway

commit control/Control.java 'void run() {}'
check_not "a change to a module changes its tag" "$("$SCRIPT" control)" "$control"
check "a change to another module leaves the tag" "$("$SCRIPT" gateway)" "$gateway"
control=$("$SCRIPT" control)

commit common/Message.java 'int amount;'
check_not "a change to common changes the tag of a module on common" "$("$SCRIPT" gateway)" "$gateway"
check "a change to common leaves the tag of a module without it" "$("$SCRIPT" control)" "$control"
gateway=$("$SCRIPT" gateway)

commit README.md 'more notes'
check "a change outside the build leaves every tag" "$("$SCRIPT" gateway) $("$SCRIPT" control)" "$gateway $control"

for file in infra/docker/Dockerfile gradle/libs.versions.toml settings.gradle.kts build.gradle.kts; do
  commit "$file" '# changed'
  check_not "a change to $file changes the tags" "$("$SCRIPT" gateway) $("$SCRIPT" control)" "$gateway $control"
  gateway=$("$SCRIPT" gateway)
  control=$("$SCRIPT" control)
done

check "an earlier commit gives the tag it had" "$("$SCRIPT" control "$(git rev-list --max-parents=0 HEAD)")" "$first_control"
if "$SCRIPT" missing >/dev/null 2>&1; then
  echo "FAILED: a missing module is refused"
  failures=$((failures + 1))
else
  echo "ok: a missing module is refused"
fi

[ "$failures" -eq 0 ] || { echo "$failures failed"; exit 1; }
echo "all passed"
