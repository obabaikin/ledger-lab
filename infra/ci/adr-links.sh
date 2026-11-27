#!/bin/sh
# Every ADR a tracked file names, as docs/adr/NNNN-title.md or as "ADR NNNN", is a file in docs/adr.
# Usage: infra/ci/adr-links.sh, from anywhere in the repository; prints each broken reference as file:line:reference
# and exits 1 if there is one.
set -eu

ADR_DIR=docs/adr
cd "$(git rev-parse --show-toplevel)"
broken=$(mktemp)
trap 'rm -f "$broken"' EXIT

git grep -n -o -E "$ADR_DIR/[0-9]{4}-[A-Za-z0-9._-]*" | while IFS= read -r hit; do
  reference=${hit#*:*:}
  [ -f "${reference%.md}.md" ] || echo "$hit" >>"$broken"
done

git grep -n -o -E 'ADR [0-9]{4}' | while IFS= read -r hit; do
  number=${hit##* }
  set -- "$ADR_DIR/$number"-*.md
  [ -f "$1" ] || echo "$hit" >>"$broken"
done

if [ -s "$broken" ]; then
  echo "references to ADRs that $ADR_DIR does not have:" >&2
  cat "$broken" >&2
  exit 1
fi
echo "every ADR reference has its file"
