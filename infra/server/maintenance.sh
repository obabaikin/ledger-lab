#!/bin/sh
# Declares or cancels a maintenance window. Bad minutes within a window do not use up the error budget, if it was declared
# early enough before its start (maintenance.noticeHours of the chart); the page shows the window to every visitor.
# Usage: infra/server/maintenance.sh add <from> <to>     times as 2026-11-12T02:00Z
#        infra/server/maintenance.sh cancel <id>         only before the window starts
#        infra/server/maintenance.sh list
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

# Whole minutes: availability is judged by the minute.
TIME_SHAPE='[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}Z'

usage() {
  echo "usage: $0 add <from> <to> | cancel <id> | list; times in UTC as 2026-11-12T02:00Z" >&2
  exit 2
}

[ $# -ge 1 ] || usage
command=$1
shift
case "$command" in
  add)
    [ $# -eq 2 ] || usage
    for time in "$1" "$2"; do
      echo "$time" | grep -Eqx "$TIME_SHAPE" || usage
    done
    require_cluster
    notice_hours=$(maintenance_notice_hours)
    psql_admin -tA -F ' ' -v from="$1" -v to="$2" -v notice="$notice_hours" <<'SQL'
INSERT INTO control.maintenance_windows (starts_at, ends_at)
VALUES (CAST(:'from' AS timestamptz), CAST(:'to' AS timestamptz))
RETURNING 'window', id, to_char(starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"'), to_char(ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"'),
          CASE WHEN declared_at <= starts_at - make_interval(hours => :notice)
               THEN 'declared in time: its bad minutes do not use the budget'
               ELSE 'declared late: its bad minutes use the budget' END;
SQL
    ;;
  cancel)
    [ $# -eq 1 ] || usage
    echo "$1" | grep -Eqx '[1-9][0-9]*' || usage
    require_cluster
    cancelled=$(psql_admin -tA -v id="$1" <<'SQL'
UPDATE control.maintenance_windows SET cancelled_at = now()
WHERE id = :id AND cancelled_at IS NULL AND starts_at > now()
RETURNING id;
SQL
)
    if [ -z "$cancelled" ]; then
      echo "no window $1 that has not started and is not cancelled" >&2
      exit 1
    fi
    echo "window $1 cancelled"
    ;;
  list)
    [ $# -eq 0 ] || usage
    require_cluster
    psql_admin -A -F ' ' <<'SQL'
SELECT id, to_char(starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"') AS from,
       to_char(ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"') AS to,
       to_char(declared_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"') AS declared,
       coalesce(to_char(cancelled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"'), '-') AS cancelled
FROM control.maintenance_windows WHERE ends_at > now() - interval '30 days' ORDER BY starts_at;
SQL
    ;;
  *) usage ;;
esac
