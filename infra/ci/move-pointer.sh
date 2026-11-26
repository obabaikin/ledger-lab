#!/bin/sh
# Moves the deploy pointer (the branch deploy, infra/flux/release.sh) to the next commit of main that has something to
# deploy, one commit a run, so every commit of main is deployed over the one before it. The pointer stays while the site
# reports another version than the pointer's (its chain is not through, or was rolled back), while the site does not
# answer, and while the commit's last completed e2e run did not pass. A commit with nothing to deploy (changed.sh) is
# passed without a pointer of its own: the site keeps the version of the last pointer, so the next run is not held.
# Usage: SITE_URL=<the site's address> REPOSITORY_URL=<git URL the cluster reads> GITHUB_REPOSITORY=<owner/name>
# infra/ci/move-pointer.sh, in a clone with the whole history; gh needs GH_TOKEN. Writes nothing but the branch deploy.
# Known limit: control reports a version as soon as its pod runs, while its own release check (the chart's
# releaseCheck.settleSeconds and its queries) is still to end; a run within that time takes the chain for done and may
# move the pointer although control is then rolled back.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
REMOTE="${REMOTE:-origin}"
MAIN_BRANCH=main
POINTER_BRANCH=deploy
POINTER_FILE=release.yaml
E2E_WORKFLOW="${E2E_WORKFLOW:-e2e.yml}"
E2E_PASSED=success
E2E_NO_RUN=none
# Above any number of e2e runs one commit gets: its push, the nightly ones until the next merge, reruns by hand.
E2E_RUNS_READ=100
SITE_TIMEOUT_SECONDS=20
# changed.sh's exit code for "nothing to deploy".
NOTHING=3

if [ $# -ne 0 ] || [ -z "${SITE_URL:-}" ] || [ -z "${REPOSITORY_URL:-}" ] || [ -z "${GITHUB_REPOSITORY:-}" ]; then
  echo "usage: SITE_URL=<address> REPOSITORY_URL=<git URL> GITHUB_REPOSITORY=<owner/name> $0" >&2
  exit 2
fi

site=$(curl -sf --max-time "$SITE_TIMEOUT_SECONDS" "$SITE_URL/api/site" | jq -er '.version | strings') || site=""
if [ -z "$site" ]; then
  echo "the site does not answer with its version: the pointer stays"
  exit 0
fi

git fetch --quiet --prune --tags "$REMOTE"
main="refs/remotes/$REMOTE/$MAIN_BRANCH"
tip=$(git rev-parse --verify --quiet "refs/remotes/$REMOTE/$POINTER_BRANCH^{commit}") || {
  echo "no branch $POINTER_BRANCH: the first pointer is set by hand"
  exit 0
}

previous=$(mktemp)
release=$(mktemp)
trap 'rm -f "$previous" "$release"' EXIT
# The pointer deployed in full is the newest one holding the site's version; the chain is through when that is the tip.
if ! "$HERE/../flux/deployed-release.sh" "$site" "$tip" >"$previous" ||
    ! git show "$tip:$POINTER_FILE" | cmp -s - "$previous"; then
  echo "the site runs $site, not the pointer's version: the pointer stays"
  exit 0
fi

# As release.sh writes it.
deployed=$(sed -n 's/^  ref: {commit: "\([0-9a-f]*\)"}$/\1/p' "$previous")
if [ -z "$deployed" ] || ! git rev-list --first-parent "$main" | grep -qxF -- "$deployed"; then
  echo "the pointer's commit '$deployed' is not a commit of $MAIN_BRANCH: the pointer is moved by hand" >&2
  exit 1
fi

e2e_conclusion() {
  runs=$(gh api --method GET "repos/$GITHUB_REPOSITORY/actions/workflows/$E2E_WORKFLOW/runs" \
    -f head_sha="$1" -f status=completed -F per_page="$E2E_RUNS_READ") || return 1
  echo "$runs" | jq -r --arg none "$E2E_NO_RUN" '.workflow_runs | sort_by(.created_at) | last | .conclusion // $none'
}

# A merge counts as the one commit main got, not as the commits of its branch.
for candidate in $(git rev-list --first-parent --reverse "$deployed..$main"); do
  status=0
  changes=$("$HERE/changed.sh" "$deployed" "$candidate") || status=$?
  if [ "$status" -eq "$NOTHING" ]; then
    echo "$candidate: nothing to deploy, passed"
    continue
  fi
  if [ "$status" -ne 0 ]; then
    echo "cannot compare $deployed with $candidate" >&2
    exit 1
  fi
  echo "$candidate:"
  echo "$changes"
  conclusion=$(e2e_conclusion "$candidate") || { echo "cannot read the e2e runs of $candidate" >&2; exit 1; }
  if [ "$conclusion" != "$E2E_PASSED" ]; then
    echo "the last completed e2e run of $candidate: $conclusion; the pointer stays"
    exit 0
  fi
  PREVIOUS_RELEASE="$previous" "$HERE/../flux/release.sh" "$candidate" "$REPOSITORY_URL" >"$release"
  blob=$(git hash-object -w "$release")
  tree=$(printf '100644 blob %s\t%s\n' "$blob" "$POINTER_FILE" | git mktree)
  pointer=$(git commit-tree "$tree" -p "$tip" -m "deploy $candidate")
  # Not forced: a pointer moved meanwhile, by hand or by another run, is kept and this run fails.
  git push --quiet "$REMOTE" "$pointer:refs/heads/$POINTER_BRANCH"
  echo "pointer moved from $deployed to $candidate"
  exit 0
done
echo "nothing to deploy after $deployed"
