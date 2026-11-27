#!/bin/sh
# Whether there is anything to deploy between two commits: a module's image tag (module-tag.sh), the chart or the
# script that writes the deploy pointer (the chain and the settings of the releases) changed.
# Usage: infra/ci/changed.sh <from> <to>; prints what changed, one line each, and exits 0 if anything did, 3 if nothing
# did ("nothing to deploy"), any other code on an error.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
CHART=infra/helm
POINTER_SCRIPT=infra/flux/release.sh
USAGE="usage: $0 <deployed commit> <new commit>"
# Not 1: a failure that set -e turns into an exit must never read as "nothing to deploy".
NOTHING=3
FAILED=2

if [ $# -ne 2 ]; then
  echo "$USAGE" >&2
  exit "$FAILED"
fi
from="$1"
to="$2"
cd "$(git rev-parse --show-toplevel)"
for commit in "$from" "$to"; do
  if ! git rev-parse --verify --quiet "$commit^{commit}" >/dev/null; then
    echo "no commit $commit" >&2
    exit "$FAILED"
  fi
done

modules() {
  git ls-tree --name-only "$1" | while IFS= read -r entry; do
    if git cat-file -e "$1:$entry/build.gradle.kts" 2>/dev/null; then echo "$entry"; fi
  done
}
tag() {
  if git cat-file -e "$2:$1/build.gradle.kts" 2>/dev/null; then
    "$HERE/module-tag.sh" "$1" "$2"
  else
    echo none
  fi
}
# A chart reading a file through a symlink out of its directory changes with the target; git versions a symlink as its
# target's path, so the targets are compared too.
chart_paths() {
  echo "$CHART"
  git ls-tree -r "$1" -- "$CHART" | while read -r mode _ object path; do
    if [ "$mode" = 120000 ]; then
      realpath -m --relative-to=. "$(dirname "$path")/$(git cat-file -p "$object")"
    fi
  done
}

changes=""
for module in $({ modules "$from"; modules "$to"; } | sort -u); do
  old=$(tag "$module" "$from")
  new=$(tag "$module" "$to")
  if [ "$old" != "$new" ]; then changes="$changes$module: $old -> $new
"; fi
done
# shellcheck disable=SC2046
if ! git diff --quiet "$from" "$to" -- $({ chart_paths "$from"; chart_paths "$to"; } | sort -u); then changes="$changes$CHART changed
"; fi
if ! git diff --quiet "$from" "$to" -- "$POINTER_SCRIPT"; then changes="$changes$POINTER_SCRIPT changed
"; fi

if [ -z "$changes" ]; then
  echo "nothing to deploy"
  exit "$NOTHING"
fi
printf %s "$changes"
