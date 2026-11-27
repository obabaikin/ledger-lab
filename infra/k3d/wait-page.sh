#!/bin/sh
# Waits until control answers behind the ingress: k3s installs its ingress controller on its own schedule, after the
# releases already report ready. Prints the pods and the page's address.
set -eu

NAMESPACE="${NAMESPACE:-ledger-lab}"
CONTROL_PORT="${CONTROL_PORT:-8083}"
INGRESS_ATTEMPTS="${INGRESS_ATTEMPTS:-90}"

attempt=0
until curl -sf -o /dev/null "http://localhost:$CONTROL_PORT/v3/api-docs"; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$INGRESS_ATTEMPTS" ]; then
    echo "control did not answer behind the ingress after $attempt attempts"
    kubectl --namespace "$NAMESPACE" get pods
    exit 1
  fi
  echo "waiting for control behind the ingress"
  sleep 2
done
kubectl --namespace "$NAMESPACE" get pods
echo "control: http://localhost:$CONTROL_PORT"
