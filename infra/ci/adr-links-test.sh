#!/bin/sh
# Checks adr-links.sh on a scratch repository: references to existing ADRs pass, a reference to a missing one fails.
set -eu

SCRIPT="$(cd "$(dirname "$0")" && pwd)/adr-links.sh"
# Built from parts so that this file holds no reference to a missing ADR itself.
MISSING=0099
ADR_DIR=docs/adr
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q
git config user.email test@example.invalid
git config user.name test

mkdir -p docs/adr infra/helm
echo '# ADR 0001: First' >"$ADR_DIR/0001-first.md"
echo "# why: $ADR_DIR/0001-first.md" >infra/helm/values.yaml
echo '/** Held as ADR 0001 says. */' >Service.java
git add -A && git commit -qm start

failures=0
passes() {
  if "$SCRIPT" >/dev/null 2>&1; then
    echo "ok: $1"
  else
    echo "FAILED: $1 (the check refused it)"
    failures=$((failures + 1))
  fi
}
fails() {
  if "$SCRIPT" >/dev/null 2>&1; then
    echo "FAILED: $1 (the check passed it)"
    failures=$((failures + 1))
  else
    echo "ok: $1"
  fi
}
plant() {
  echo "$2" >"$1"
  git add "$1"
}
unplant() {
  git rm -qf "$1"
}

passes "references to an existing ADR by path and by number pass"

plant infra/helm/broken.yaml "# see docs/adr/$MISSING-missing.md"
fails "a path to a missing ADR fails"
unplant infra/helm/broken.yaml

plant infra/helm/broken.yaml "# see (docs/adr/$MISSING-missing)."
fails "a path to a missing ADR without .md fails"
unplant infra/helm/broken.yaml

plant broken.sh "# see $ADR_DIR/0001-renamed.md"
fails "a path with the wrong title of an existing ADR fails"
unplant broken.sh

plant Broken.java "/** As ADR $MISSING says. */"
fails "a number of a missing ADR fails"
unplant Broken.java

mkdir -p deep && cd deep
passes "the check runs from a subdirectory of the repository"
cd ..

plant infra/helm/broken.yaml "# docs/adr/$MISSING-missing.md"
output=$("$SCRIPT" 2>&1 || true)
case "$output" in
*"infra/helm/broken.yaml:1:docs/adr/$MISSING-missing.md"*) echo "ok: a broken reference is named with its file and line" ;;
*)
  echo "FAILED: a broken reference is named with its file and line (got: $output)"
  failures=$((failures + 1))
  ;;
esac

[ "$failures" -eq 0 ] || { echo "$failures failed"; exit 1; }
echo "all passed"
