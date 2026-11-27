#!/bin/sh
# The image tag of a module: a hash of what its image is built from. A release that leaves a module's files as they were
# gives it the tag it had, so its pods are not replaced.
# Usage: infra/ci/module-tag.sh <module> [<commit> | --worktree]; the default is HEAD, --worktree hashes the files as they
# are now, committed or not (git's ignored files left out).
set -eu

TAG_LENGTH=12
USAGE="usage: $0 <module directory, e.g. ledger-service> [<commit> | --worktree]"

module="${1:-}"
source="${2:-HEAD}"
if [ -z "$module" ] || [ $# -gt 2 ]; then
  echo "$USAGE" >&2
  exit 2
fi
cd "$(git rev-parse --show-toplevel)"

if [ "$source" = --worktree ]; then
  index=$(mktemp)
  trap 'rm -f "$index"' EXIT
  GIT_INDEX_FILE="$index" git read-tree HEAD
  GIT_INDEX_FILE="$index" git add -A
  tree=$(GIT_INDEX_FILE="$index" git write-tree)
else
  tree=$(git rev-parse --verify --quiet "$source^{tree}") || {
    echo "no commit $source" >&2
    exit 1
  }
fi

if ! git cat-file -e "$tree:$module/build.gradle.kts" 2>/dev/null; then
  echo "no module $module in $source" >&2
  exit 1
fi
paths="$module infra/docker/Dockerfile build.gradle.kts settings.gradle.kts gradle gradle.properties"
if git cat-file -p "$tree:$module/build.gradle.kts" | grep -q 'project(":common")'; then
  paths="$paths common"
fi
# shellcheck disable=SC2086
git ls-tree "$tree" -- $paths | sha256sum | cut -c1-"$TAG_LENGTH"
