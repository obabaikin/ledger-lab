#!/bin/sh
# The release.yaml of the last pointer whose whole chain was deployed: the newest commit of the deploy branch holding
# the version that control reports at /api/site. Control is the last release of the chain, so it runs a pointer's
# version only once every release before it does. release.sh compares a new pointer with this file (PREVIOUS_RELEASE).
# Usage: infra/flux/deployed-release.sh <version from /api/site> [<branch or commit of the pointer>], in the repository
# that holds the pointer (or with GIT_DIR). Prints the file; exits 1 when no pointer holds the version.
set -eu

POINTER_FILE=release.yaml
POINTER="${2:-deploy}"

if [ $# -lt 1 ] || [ $# -gt 2 ] || [ -z "$1" ]; then
  echo "usage: $0 <version> [<branch or commit of the pointer>]" >&2
  exit 2
fi
# As release.sh writes it into every release's values.
version_line="    version: \"$1\""

git rev-parse --verify --quiet "$POINTER^{commit}" >/dev/null || { echo "no pointer $POINTER" >&2; exit 2; }
for pointer in $(git rev-list "$POINTER"); do
  if git show "$pointer:$POINTER_FILE" 2>/dev/null | grep -qxF -- "$version_line"; then
    git show "$pointer:$POINTER_FILE"
    exit 0
  fi
done
echo "no pointer in $POINTER holds version $1" >&2
exit 1
