#!/bin/sh
# The five service images of a source tree in the k3d node, each tagged with the hash of its module's files as they are
# in the tree (the tree's module-tag.sh --worktree): one the node has is kept, one the local docker has is imported, one
# in the registry (IMAGE_REPOSITORY set, as in CI) is pulled and imported, and the rest are built from the tree.
# IMAGES_SOURCE=registry: every image is pulled from IMAGE_REPOSITORY, whatever the node or the local docker has, and a
# failed pull is an error (CI on main tests exactly the images the server will pull).
# Usage: CLUSTER=<name> infra/k3d/images.sh [<tree>]; prints "<service>=<tag>" per service, the rest goes to stderr.
set -eu

CLUSTER="${CLUSTER:-ledger-lab}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TREE="$(cd "${1:-$ROOT}" && pwd)"
NODE="k3d-$CLUSTER-server-0"
IMAGES_SOURCE="${IMAGES_SOURCE:-any}"
SOURCE_REGISTRY=registry
# Kept in step with services.<service>.module in the chart's values: the chart names a service's image by its module.
SERVICES="ledger:ledger-service gateway:gateway generator:generator control:control reporting:reporting"
# The tree's own rule names its images, as its CI pushed them; a tree from before the rule takes this one's.
MODULE_TAG="$TREE/infra/ci/module-tag.sh"
[ -x "$MODULE_TAG" ] || MODULE_TAG="$ROOT/infra/ci/module-tag.sh"

case "$IMAGES_SOURCE" in any | "$SOURCE_REGISTRY") ;; *) echo "IMAGES_SOURCE: any or $SOURCE_REGISTRY" >&2; exit 2 ;; esac

builds=""
images=""
for pair in $SERVICES; do
  service="${pair%%:*}"
  module="${pair#*:}"
  tag=$(cd "$TREE" && "$MODULE_TAG" "$module" --worktree)
  image="ledger-lab/$module:$tag"
  if [ "$IMAGES_SOURCE" = "$SOURCE_REGISTRY" ]; then
    if [ -z "${IMAGE_REPOSITORY:-}" ] || ! pulled=$(docker pull -q "$IMAGE_REPOSITORY/$module:$tag" 2>&1); then
      echo "$module:$tag: not pulled from ${IMAGE_REPOSITORY:-IMAGE_REPOSITORY (not set)}: ${pulled:-}" >&2
      exit 1
    fi
    docker tag "$IMAGE_REPOSITORY/$module:$tag" "$image"
    images="$images $image"
  elif docker exec "$NODE" crictl inspecti "docker.io/$image" >/dev/null 2>&1; then
    :
  elif docker image inspect "$image" >/dev/null 2>&1; then
    images="$images $image"
  elif [ -n "${IMAGE_REPOSITORY:-}" ] && docker pull -q "$IMAGE_REPOSITORY/$module:$tag" >&2 2>/dev/null; then
    docker tag "$IMAGE_REPOSITORY/$module:$tag" "$image"
    images="$images $image"
  else
    builds="$builds $module:$tag"
  fi
  echo "$service=$tag"
done
if [ -n "$builds" ]; then
  tasks=""
  for build in $builds; do
    module="${build%%:*}"
    # A jar of an earlier version left in build/libs would give the image build two to choose from.
    rm -f "$TREE/$module"/build/libs/*.jar
    tasks="$tasks :$module:bootJar"
  done
  echo "building$builds from $TREE" >&2
  # shellcheck disable=SC2086
  (cd "$TREE" && ./gradlew $tasks -q) >&2
  for build in $builds; do
    module="${build%%:*}"
    docker build -q -f "$TREE/infra/docker/Dockerfile" -t "ledger-lab/$build" "$TREE/$module/build/libs" >&2
    images="$images ledger-lab/$build"
  done
fi
if [ -n "$images" ]; then
  # shellcheck disable=SC2086
  k3d image import --cluster "$CLUSTER" $images >&2
fi
