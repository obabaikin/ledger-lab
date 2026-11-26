#!/bin/sh
# Deploys a commit to the k3d cluster the way the server is deployed: Flux follows a deploy branch whose release.yaml
# names the commit (infra/flux/release.sh). The branch and the commit live in a bare repository under build/, served to
# the cluster over HTTP (infra/k3d/git-http.py), so the working repository and its remote are left as they are.
# Usage: infra/k3d/flux.sh [<commit>] [<values file in the repository> ...]; the commit defaults to HEAD. Returns once
# the pointer moved; WAIT=1 also waits until the last release of the chain is ready on the new revision.
# The deploy window is off, so the pointer is applied at once; WINDOW=on keeps it (schedules: infra/flux/window.sh).
# Which services get the release check is decided against the pointer the cluster runs in full: the one holding the
# version control reports (infra/flux/deployed-release.sh). While control does not answer, no service is checked.
# Needs docker, k3d, kubectl, helm, git, curl and python3 on PATH.
set -eu

CLUSTER="${CLUSTER:-ledger-lab}"
NAMESPACE="${NAMESPACE:-ledger-lab}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GIT_BASE="$ROOT/build/flux-git"
REPOSITORY=ledger-lab.git
TREES="$ROOT/build/flux-trees"
SERVER_PID="$GIT_BASE/server.pid"
GIT_PORT="${GIT_PORT:-9418}"
POINTER_BRANCH=deploy
POINTER_FILE=release.yaml
FLUX_NAMESPACE=flux-system
LAST_RELEASE=control
CONTROL_PORT="${CONTROL_PORT:-8083}"
SITE_TIMEOUT_SECONDS=5
CHAIN_TIMEOUT_SECONDS="${CHAIN_TIMEOUT_SECONDS:-3600}"
# The name k3d gives the host inside the cluster.
HOST_IN_CLUSTER=host.k3d.internal

commit=$(git -C "$ROOT" rev-parse --verify --quiet "${1:-HEAD}^{commit}") || { echo "no commit ${1:-HEAD}" >&2; exit 2; }
[ $# -gt 0 ] && shift
url="http://$HOST_IN_CLUSTER:$GIT_PORT/$REPOSITORY"

CLUSTER="$CLUSTER" "$ROOT/infra/k3d/cluster.sh"
if helm status ledger-lab --namespace "$NAMESPACE" >/dev/null 2>&1; then
  echo "the release ledger-lab (infra/k3d/up.sh) owns the bank's objects; uninstall it, or move it to the six releases" >&2
  exit 1
fi

# The images of the commit, built from a checkout of it: the working tree may hold changes the commit has not.
tree="$TREES/$commit"
git -C "$ROOT" worktree remove --force "$tree" 2>/dev/null || true
git -C "$ROOT" worktree add -q --detach "$tree" "$commit"
CLUSTER="$CLUSTER" "$ROOT/infra/k3d/images.sh" "$tree" >/dev/null
git -C "$ROOT" worktree remove --force "$tree"

bare="$GIT_BASE/$REPOSITORY"
if [ ! -d "$bare" ]; then
  git init -q --bare "$bare"
fi
# Flux clones the default branch to find a commit named without one, as on GitHub.
git -C "$bare" symbolic-ref HEAD refs/heads/main
git -C "$ROOT" push -q -f "$bare" "$commit:refs/heads/main"

# The pointer: one commit on the deploy branch with release.yaml alone, made without a checkout.
release=$(mktemp)
previous=$(mktemp)
trap 'rm -f "$release" "$previous"' EXIT
parent=$(git -C "$bare" rev-parse --verify --quiet "refs/heads/$POINTER_BRANCH" || true)
deployed=""
if [ -n "$parent" ]; then
  deployed=$(curl -sf --max-time "$SITE_TIMEOUT_SECONDS" "http://localhost:$CONTROL_PORT/api/site" |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["version"])' 2>/dev/null || true)
  if [ -z "$deployed" ]; then
    echo "control does not answer with its version: no service gets the release check" >&2
  elif ! GIT_DIR="$bare" "$ROOT/infra/flux/deployed-release.sh" "$deployed" "$POINTER_BRANCH" >"$previous"; then
    echo "no service gets the release check" >&2
    deployed=""
  fi
fi
if [ -n "$deployed" ]; then
  (cd "$ROOT" && PREVIOUS_RELEASE="$previous" infra/flux/release.sh "$commit" "$url" "$@") >"$release"
else
  (cd "$ROOT" && infra/flux/release.sh "$commit" "$url" "$@") >"$release"
fi
blob=$(git -C "$bare" hash-object -w "$release")
tree_object=$(printf '100644 blob %s\t%s\n' "$blob" "$POINTER_FILE" | git -C "$bare" mktree)
pointer=$(GIT_AUTHOR_NAME=k3d GIT_AUTHOR_EMAIL=k3d@localhost GIT_COMMITTER_NAME=k3d GIT_COMMITTER_EMAIL=k3d@localhost \
  git -C "$bare" commit-tree "$tree_object" ${parent:+-p "$parent"} -m "deploy $commit")
git -C "$bare" update-ref "refs/heads/$POINTER_BRANCH" "$pointer"

# Served on the k3d network's gateway only: that is the host as the cluster sees it.
gateway=$(docker network inspect "k3d-$CLUSTER" -f '{{(index .IPAM.Config 0).Gateway}}')
if [ ! -f "$SERVER_PID" ] || ! kill -0 "$(cat "$SERVER_PID")" 2>/dev/null; then
  setsid python3 "$ROOT/infra/k3d/git-http.py" "$GIT_BASE" "$gateway" "$GIT_PORT" >>"$GIT_BASE/server.log" 2>&1 &
  echo $! >"$SERVER_PID"
fi

NAMESPACE="$NAMESPACE" WINDOW="${WINDOW:-off}" "$ROOT/infra/flux/install.sh" "$url"
requested=$(date +%s)
kubectl --namespace "$FLUX_NAMESPACE" annotate --overwrite gitrepository "$POINTER_BRANCH" \
  "reconcile.fluxcd.io/requestedAt=$requested" >/dev/null
echo "pointer at $commit"

if [ "${WAIT:-0}" = 1 ]; then
  # The last release reports the commit's version once the whole chain before it is done.
  version=$(git -C "$ROOT" describe --tags --match 'v[0-9]*' --long --always "$commit")
  # Flux names a chart built from a commit <version>+<12 characters of the commit>.<n>.
  revision="+${commit%"${commit#????????????}"}."
  deadline=$(($(date +%s) + CHAIN_TIMEOUT_SECONDS))
  until kubectl --namespace "$NAMESPACE" get "helmrelease/$LAST_RELEASE" \
      -o jsonpath='{.status.history[0].chartVersion} {.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null |
      grep -qF -- "$revision"; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "$LAST_RELEASE did not reach $commit within ${CHAIN_TIMEOUT_SECONDS}s" >&2
      exit 1
    fi
    sleep 5
  done
  kubectl --namespace "$NAMESPACE" wait "helmrelease/$LAST_RELEASE" --timeout=5m --for=condition=Ready >/dev/null
  echo "deployed $version"
  NAMESPACE="$NAMESPACE" "$ROOT/infra/k3d/wait-page.sh"
fi
