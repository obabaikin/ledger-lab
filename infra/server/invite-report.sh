#!/bin/sh
# Who used their invite: visits, sessions and runs per invite, labelled from the local invites file;
# also exports the invite, session and run tables as CSV.
# Usage: infra/server/invite-report.sh
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

INVITES="$CONFIG_DIR/invites.csv"
EXPORT_DIR="${EXPORT_ROOT:-$HOME/.local/share/ledger-lab}/export-$(date +%Y%m%d-%H%M%S)"
EXPORTED_TABLES="invite_tokens invite_visits sessions runs"

require_cluster

stats=$(psql_admin --csv -t <<SQL
SELECT t.token,
       coalesce(v.count, 0),
       coalesce(to_char(v.first_seen AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'), '-'),
       coalesce(to_char(v.last_seen AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'), '-'),
       (SELECT count(*) FROM control.sessions s WHERE s.token = t.token),
       (SELECT count(*) FROM control.runs r WHERE r.token = t.token),
       coalesce((SELECT to_char(max(r.created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI')
                 FROM control.runs r WHERE r.token = t.token), '-'),
       (SELECT count(*) FROM control.runs r WHERE r.token = t.token AND r.state IN ($ACTIVE_RUN_STATES))
FROM control.invite_tokens t
LEFT JOIN control.invite_visits v USING (token)
ORDER BY t.created_at;
SQL
)

echo "times in UTC"
{
  echo "label,code,visits,first seen,last seen,sessions,runs,last run,active runs"
  echo "$stats" | awk -F , -v OFS=, -v invites="$INVITES" '
    BEGIN { while ((getline line < invites) > 0) { split(line, f, ","); label[f[1]] = f[2] } }
    NF { print ($1 in label ? label[$1] : "-"), $0 }'
} | column -t -s ,

mkdir -p "$EXPORT_DIR"
for table in $EXPORTED_TABLES; do
  psql_admin -c "COPY control.$table TO STDOUT WITH (FORMAT csv, HEADER)" >"$EXPORT_DIR/$table.csv"
done
echo "exported to $EXPORT_DIR"
