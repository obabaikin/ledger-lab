#!/bin/sh
# The modules whose image for a commit is not in the registry yet: the image is named by the module's tag
# (module-tag.sh), so a module whose files are as they were at an earlier pushed commit already has its image.
# Usage: IMAGE_REPOSITORY=ghcr.io/<owner>/<repo> MODULES="<module> ..." infra/ci/missing-images.sh [<commit>];
# prints "<module> <tag>" for each missing one, nothing if all are there; reads the registry as docker is logged in.
# Exits 1 when the registry cannot be read: only its answer "not found" means the image is missing.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
USAGE="usage: IMAGE_REPOSITORY=<registry/owner/repo> MODULES=\"<module> ...\" $0 [<commit>]"

if [ -z "${IMAGE_REPOSITORY:-}" ] || [ -z "${MODULES:-}" ] || [ $# -gt 1 ]; then
  echo "$USAGE" >&2
  exit 2
fi
commit="${1:-HEAD}"
NOT_FOUND_ANSWER="not found"

for module in $MODULES; do
  tag=$("$HERE/module-tag.sh" "$module" "$commit")
  image="$IMAGE_REPOSITORY/$module:$tag"
  if ! answer=$(docker buildx imagetools inspect "$image" 2>&1 >/dev/null); then
    case "$answer" in
      *"$NOT_FOUND_ANSWER"*) echo "$module $tag" ;;
      *) echo "cannot read $image from the registry: $answer" >&2; exit 1 ;;
    esac
  fi
done
