# shellcheck shell=sh disable=SC2034
# Sourced by the operator scripts: local settings, the server's cluster through the SSH tunnel, psql in its Postgres pod.
CONFIG_DIR="${CONFIG_DIR:-$HOME/.config/ledger-lab}"
SERVER_ENV="$CONFIG_DIR/server.env"
if [ -f "$SERVER_ENV" ]; then
  # shellcheck source=/dev/null
  . "$SERVER_ENV"
fi
KUBECONFIG="${SERVER_KUBECONFIG:-$HOME/.kube/ledger-lab-server.yaml}"
export KUBECONFIG
NAMESPACE="${NAMESPACE:-ledger-lab}"
TUNNEL_PORT="${TUNNEL_PORT:-16443}"
ACTIVE_RUN_STATES="'QUEUED', 'CREATED', 'RUNNING', 'DRAINING'"

require_cluster() {
  if [ ! -f "$KUBECONFIG" ]; then
    echo "no kubeconfig at $KUBECONFIG: run infra/server/bootstrap.sh" >&2
    exit 1
  fi
  if ! kubectl get --raw /readyz --request-timeout=5s </dev/null >/dev/null 2>&1; then
    echo "the cluster does not answer; open the tunnel: ssh -fNL $TUNNEL_PORT:127.0.0.1:6443 root@${SERVER:-<server ip>}" >&2
    exit 1
  fi
}

require_domain() {
  if [ -z "${DOMAIN:-}" ]; then
    echo "DOMAIN is not set: add DOMAIN=<domain> to $SERVER_ENV" >&2
    exit 1
  fi
}

psql_admin() {
  kubectl --namespace "$NAMESPACE" exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -q -U admin -d ledgerlab "$@"
}

# Gives every role under passwords: in the chart's values a random password in the local file, keeping those it has:
# a role added to the chart later gets one too, instead of the chart's placeholder.
ensure_passwords() {
  chart_values="$1"
  passwords_file="$2"
  roles=$(sed -n '/^passwords:/,/^[^ ]/s/^  \([a-z][a-z0-9_]*\): .*/\1/p' "$chart_values")
  mkdir -p "$(dirname "$passwords_file")"
  (
    umask 077
    if [ ! -f "$passwords_file" ]; then
      echo "passwords:" >"$passwords_file"
      echo "generated $passwords_file: keep a copy, the database roles are created with these passwords" >&2
    fi
    # A file edited by hand may end without a newline, and the next role would join its last line.
    [ -z "$(tail -c1 "$passwords_file")" ] || echo >>"$passwords_file"
    for role in $roles; do
      if ! grep -q "^  $role: " "$passwords_file"; then
        password=$(openssl rand -hex 24) || exit 1
        echo "  $role: $password" >>"$passwords_file"
        echo "added a password for $role to $passwords_file" >&2
      fi
    done
  )
}
