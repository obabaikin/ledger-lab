#!/bin/sh
# Creates an invite and prints its link. The label (who it is for) stays in a local file, never on the server.
# Usage: infra/server/create-invite.sh <days valid> <runs per hour> <label>
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

INVITES="$CONFIG_DIR/invites.csv"
TOKEN_PREFIX=look-

if [ $# -ne 3 ]; then
  echo "usage: $0 <days valid> <runs per hour> <label>, e.g. $0 7 20 'meetup-2026-10'" >&2
  exit 2
fi
days=$1 quota=$2 label=$3
for number in "$days" "$quota"; do
  if ! echo "$number" | grep -Eqx '[1-9][0-9]*'; then
    echo "days and runs per hour are positive whole numbers, got '$number'" >&2
    exit 2
  fi
done
case "$label" in
  *,* | *'"'* | *"
"*) echo "the label goes into a CSV file: no commas, quotes or line breaks" >&2; exit 2 ;;
esac
require_domain
require_cluster

token="$TOKEN_PREFIX$(uuidgen | tr '[:upper:]' '[:lower:]')"
row=$(psql_admin -tA -F , -v token="$token" -v days="$days" -v quota="$quota" <<'SQL'
INSERT INTO control.invite_tokens (token, expires_at, quota)
VALUES (:'token', now() + make_interval(days => :days), :quota)
RETURNING to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"'),
          to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"');
SQL
)

mkdir -p "$CONFIG_DIR"
[ -f "$INVITES" ] || echo "code,label,created,expires" >"$INVITES"
echo "$token,$label,$row" >>"$INVITES"
echo "https://$DOMAIN/?ref=$token"
