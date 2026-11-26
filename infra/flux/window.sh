#!/bin/sh
# The deploy window: the hours' minutes in which a new pointer may start a deploy. Outside it the Kustomization that
# applies the pointer is suspended; a chain of releases that has started is not held by it and runs to its end.
# Prints the manifest of the two CronJobs that open and close the window and of their rights; install.sh applies it.
# WINDOW_OPENS and WINDOW_CLOSES take the place of the two schedules (cron, UTC).
set -eu

FLUX_NAMESPACE=flux-system
POINTER=deploy
# The CLI of the Flux that install.sh puts in the cluster (the app version of its FLUX_CHART_VERSION).
FLUX_CLI_IMAGE=ghcr.io/fluxcd/flux-cli:v2.9.5
MEMORY=64Mi
ACCOUNT=deploy-window
OPENS="${WINDOW_OPENS:-10 * * * *}"
CLOSES="${WINDOW_CLOSES:-20 * * * *}"

cat <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: $ACCOUNT
  namespace: $FLUX_NAMESPACE
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: $ACCOUNT
  namespace: $FLUX_NAMESPACE
rules:
  - apiGroups: [kustomize.toolkit.fluxcd.io]
    resources: [kustomizations]
    resourceNames: [$POINTER]
    verbs: [get, patch]
  # flux suspend and resume list before they patch, and a list cannot be narrowed to one name.
  - apiGroups: [kustomize.toolkit.fluxcd.io]
    resources: [kustomizations]
    verbs: [list]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: $ACCOUNT
  namespace: $FLUX_NAMESPACE
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: $ACCOUNT
subjects:
  - kind: ServiceAccount
    name: $ACCOUNT
    namespace: $FLUX_NAMESPACE
EOF

window_job() {
  cat <<EOF
---
apiVersion: batch/v1
kind: CronJob
metadata:
  name: $ACCOUNT-$1
  namespace: $FLUX_NAMESPACE
spec:
  schedule: "$2"
  timeZone: Etc/UTC
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 1
  failedJobsHistoryLimit: 1
  jobTemplate:
    spec:
      backoffLimit: 2
      template:
        spec:
          serviceAccountName: $ACCOUNT
          restartPolicy: Never
          securityContext:
            runAsNonRoot: true
            runAsUser: 65534
            seccompProfile: {type: RuntimeDefault}
          containers:
            - name: flux
              image: $FLUX_CLI_IMAGE
              args: [$3, kustomization, $POINTER, --namespace, $FLUX_NAMESPACE]
              securityContext:
                allowPrivilegeEscalation: false
                capabilities: {drop: [ALL]}
              resources:
                requests: {memory: $MEMORY}
                limits: {memory: $MEMORY}
EOF
}
window_job open "$OPENS" resume
window_job close "$CLOSES" suspend
