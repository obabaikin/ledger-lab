#!/bin/sh
# Flux in the cluster and the one thing it watches: the deploy pointer, a branch holding release.yaml (release.sh).
# What it reports of the releases goes to the chart's Alertmanager (notifications.sh), and the chart's Prometheus may
# read Flux's pods, where it finds helm-controller's metrics, and the chart's kube-state-metrics the Jobs of the window.
# Usage: infra/flux/install.sh <git URL of the repository with the deploy branch>; safe to run again.
# The pointer is applied only in the deploy window (window.sh); WINDOW=off leaves it applied at any time and removes
# the window's jobs.
# FLUX_INSTALL_TIMEOUT: how long Flux's own pods may take to start; a node that has none of their images needs more.
# Needs kubectl and helm on PATH, pointed at the cluster.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
FLUX_NAMESPACE=flux-system
FLUX_CHART=fluxcd-community/flux2
FLUX_CHART_REPOSITORY=https://fluxcd-community.github.io/helm-charts
FLUX_CHART_VERSION=2.20.0
POINTER_BRANCH=deploy
POINTER_INTERVAL=1m
NAMESPACE="${NAMESPACE:-ledger-lab}"
# The chart's ServiceAccount of Prometheus.
PROMETHEUS_ACCOUNT=prometheus
METRICS_READER=prometheus-pods
# The chart's ServiceAccount of kube-state-metrics.
STATE_ACCOUNT=kube-state-metrics
STATE_READER=kube-state-metrics
WINDOW_OFF=off
WINDOW="${WINDOW:-on}"
FLUX_INSTALL_TIMEOUT="${FLUX_INSTALL_TIMEOUT:-5m}"

if [ $# -ne 1 ]; then
  echo "usage: $0 <git URL of the repository with the $POINTER_BRANCH branch>" >&2
  exit 2
fi
url="$1"

helm repo add fluxcd-community "$FLUX_CHART_REPOSITORY" --force-update >/dev/null
# --no-hooks: the chart's pre-install check runs a pod without a memory limit, and the chart offers none to set.
helm upgrade --install flux "$FLUX_CHART" --version "$FLUX_CHART_VERSION" --namespace "$FLUX_NAMESPACE" \
  --create-namespace --no-hooks -f "$HERE/flux-values.yaml" --wait --timeout "$FLUX_INSTALL_TIMEOUT"

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -f - <<EOF
apiVersion: source.toolkit.fluxcd.io/v1
kind: GitRepository
metadata:
  name: $POINTER_BRANCH
  namespace: $FLUX_NAMESPACE
spec:
  interval: $POINTER_INTERVAL
  url: $url
  ref: {branch: $POINTER_BRANCH}
---
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: $POINTER_BRANCH
  namespace: $FLUX_NAMESPACE
spec:
  interval: $POINTER_INTERVAL
  sourceRef: {kind: GitRepository, name: $POINTER_BRANCH}
  path: ./
  # A release left out of the pointer by mistake must not uninstall a service with its data; removing one is done by hand.
  prune: false
---
# A Role in Flux's namespace, not a ClusterRole of the chart: Prometheus reads the pods of this namespace only.
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: $METRICS_READER
  namespace: $FLUX_NAMESPACE
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: $METRICS_READER
  namespace: $FLUX_NAMESPACE
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: $METRICS_READER
subjects:
  - kind: ServiceAccount
    name: $PROMETHEUS_ACCOUNT
    namespace: $NAMESPACE
---
# The chart's kube-state-metrics reads the deploy window's Jobs here. It asks for every kind it reads in every
# namespace it is given, so for the HelmReleases too, of which this namespace has none.
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: $STATE_READER
  namespace: $FLUX_NAMESPACE
rules:
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["list", "watch"]
  - apiGroups: ["helm.toolkit.fluxcd.io"]
    resources: ["helmreleases"]
    verbs: ["list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: $STATE_READER
  namespace: $FLUX_NAMESPACE
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: $STATE_READER
subjects:
  - kind: ServiceAccount
    name: $STATE_ACCOUNT
    namespace: $NAMESPACE
EOF

NAMESPACE="$NAMESPACE" "$HERE/notifications.sh" | kubectl apply -f -

if [ "$WINDOW" = "$WINDOW_OFF" ]; then
  "$HERE/window.sh" | kubectl delete --ignore-not-found -f - >/dev/null
  # The window may have been closed when its jobs went.
  kubectl --namespace "$FLUX_NAMESPACE" patch kustomization "$POINTER_BRANCH" --type merge -p '{"spec":{"suspend":false}}' >/dev/null
else
  "$HERE/window.sh" | kubectl apply -f -
fi
