#!/bin/sh
# Opens the SSH tunnel to the server's cluster API in the background, once; the other scripts reach the cluster through it.
# Usage: infra/server/tunnel-open.sh
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

require_server
if ssh -S "$TUNNEL_SOCKET" -O check "root@$SERVER" 2>/dev/null; then
  echo "the tunnel is already open on port $TUNNEL_PORT"
  exit 0
fi
mkdir -p "$(dirname "$TUNNEL_SOCKET")"
# A socket left by a crash would make ssh open the tunnel without a master, which tunnel-close.sh cannot end.
rm -f "$TUNNEL_SOCKET"
ssh -fNM -S "$TUNNEL_SOCKET" -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes \
  -L "$TUNNEL_PORT:127.0.0.1:6443" "root@$SERVER"
echo "the tunnel is open on port $TUNNEL_PORT; close it with infra/server/tunnel-close.sh"
