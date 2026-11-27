#!/bin/sh
# What Flux reports of the releases, sent to the chart's Alertmanager, which mails the ends of a deploy (the chart's
# templates/alertmanager.yaml chooses which). Prints the manifest of the Provider and the Alert; install.sh applies it.
# CHART_VALUES names the chart's values file, whose alertmanager.port is the port Alertmanager listens on.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
NAMESPACE="${NAMESPACE:-ledger-lab}"
NAME=deploy-mail
CHART_VALUES="${CHART_VALUES:-$HERE/../helm/ledger-lab/values.yaml}"

port=$(sed -n '/^alertmanager:$/,/^[a-z]/s/^  port: \([0-9]*\)$/\1/p' "$CHART_VALUES")
if [ -z "$port" ]; then
  echo "no alertmanager.port in $CHART_VALUES" >&2
  exit 1
fi
# The chart's Service of Alertmanager and Alertmanager's path for new alerts.
ALERTMANAGER_URL="http://alertmanager.$NAMESPACE:$port/api/v2/alerts/"

cat <<EOF_MANIFEST
apiVersion: notification.toolkit.fluxcd.io/v1beta3
kind: Provider
metadata:
  name: $NAME
  namespace: $NAMESPACE
spec:
  type: alertmanager
  address: $ALERTMANAGER_URL
---
apiVersion: notification.toolkit.fluxcd.io/v1beta3
kind: Alert
metadata:
  name: $NAME
  namespace: $NAMESPACE
spec:
  providerRef: {name: $NAME}
  # info also lets the errors through: a deploy's good end is an event as well as its failure.
  eventSeverity: info
  eventSources:
    - {kind: HelmRelease, name: "*"}
EOF_MANIFEST
