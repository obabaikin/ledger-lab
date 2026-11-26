#!/bin/sh
# What Flux reports of the releases, sent to the chart's Alertmanager, which mails the ends of a deploy (the chart's
# templates/alertmanager.yaml chooses which). Prints the manifest of the Provider and the Alert; install.sh applies it.
set -eu

NAMESPACE="${NAMESPACE:-ledger-lab}"
NAME=deploy-mail
# The chart's Service of Alertmanager (alertmanager.port in its values) and Alertmanager's path for new alerts.
ALERTMANAGER_URL="http://alertmanager.$NAMESPACE:9093/api/v2/alerts/"

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
