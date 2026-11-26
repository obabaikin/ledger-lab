#!/bin/sh
# Checks missing-images.sh against a stand-in registry: a docker on PATH that knows the images listed in a file.
set -eu

SCRIPT="$(cd "$(dirname "$0")" && pwd)/missing-images.sh"
TAG_SCRIPT="$(cd "$(dirname "$0")" && pwd)/module-tag.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export IMAGE_REPOSITORY=registry.invalid/owner/repo
export MODULES="gateway control"
export REGISTRY="$work/registry"
: >"$REGISTRY"

mkdir "$work/bin"
cat >"$work/bin/docker" <<'STUB'
#!/bin/sh
[ "$1 $2 $3" = "buildx imagetools inspect" ] || exit 2
if [ -n "${REGISTRY_DOWN:-}" ]; then
  echo "ERROR: failed to do request: dial tcp: connection refused" >&2
  exit 1
fi
grep -qxF "$4" "$REGISTRY" && exit 0
echo "ERROR: $4: not found" >&2
exit 1
STUB
chmod +x "$work/bin/docker"
PATH="$work/bin:$PATH"

mkdir "$work/repo"
cd "$work/repo"
git init -q
git config user.email test@example.invalid
git config user.name test
mkdir -p common gateway control infra/docker
echo 'implementation(project(":common"))' >gateway/build.gradle.kts
echo 'plugins { java }' >control/build.gradle.kts
echo 'class Message' >common/Message.java
echo 'class Gateway' >gateway/Gateway.java
echo 'class Control' >control/Control.java
echo 'FROM jre' >infra/docker/Dockerfile
echo 'notes' >README.md
git add -A && git commit -qm start

failures=0
check() {
  if [ "$2" = "$3" ]; then
    echo "ok: $1"
  else
    echo "FAILED: $1 (got \"$2\", expected \"$3\")"
    failures=$((failures + 1))
  fi
}
push() {
  echo "$IMAGE_REPOSITORY/$1:$("$TAG_SCRIPT" "$1")" >>"$REGISTRY"
}

gateway=$("$TAG_SCRIPT" gateway)
control=$("$TAG_SCRIPT" control)
check "an empty registry misses every module" "$("$SCRIPT")" "gateway $gateway
control $control"

push gateway
check "a module whose image is there is skipped" "$("$SCRIPT")" "control $control"

push control
check "nothing is missing once every image is there" "$("$SCRIPT")" ""

echo 'more notes' >>README.md
git commit -qam notes
check "a commit that changes no module needs no image" "$("$SCRIPT")" ""

echo 'void run() {}' >>control/Control.java
git commit -qam control
check "a changed module needs its image, the others not" "$("$SCRIPT")" "control $("$TAG_SCRIPT" control)"
check "an earlier commit's images are all there" "$("$SCRIPT" HEAD~1)" ""

if REGISTRY_DOWN=1 "$SCRIPT" >"$work/out" 2>"$work/err"; then
  echo "FAILED: a registry that cannot be read fails the call"
  failures=$((failures + 1))
else
  check "a registry that cannot be read fails the call, naming no module as missing" "$(cat "$work/out")" ""
  check "the failure tells the registry's answer" "$(grep -c 'connection refused' "$work/err")" "1"
fi

if IMAGE_REPOSITORY='' "$SCRIPT" >/dev/null 2>&1; then
  echo "FAILED: a call without the registry is refused"
  failures=$((failures + 1))
else
  echo "ok: a call without the registry is refused"
fi

[ "$failures" -eq 0 ] || { echo "$failures failed"; exit 1; }
echo "all passed"
