#!/bin/sh
# Closes the SSH tunnel opened by tunnel-open.sh.
# Usage: infra/server/tunnel-close.sh
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

require_server
if ! ssh -S "$TUNNEL_SOCKET" -O check "root@$SERVER" 2>/dev/null; then
  echo "no tunnel is open"
  exit 0
fi
ssh -S "$TUNNEL_SOCKET" -O exit "root@$SERVER"
