#!/bin/sh
# The deploy window: the hours' minutes in which a new pointer may start a deploy. Outside it the Kustomization that
# applies the pointer is suspended; a chain of releases that has started is not held by it and runs to its end.
# Prints the manifest of the two CronJobs that open and close the window and of their rights; install.sh applies it.
# WINDOW_OPENS and WINDOW_CLOSES take the place of the two schedules (cron, UTC); a window shorter than the default
# start deadline needs a shorter WINDOW_START_DEADLINE_SECONDS too.
set -eu

FLUX_NAMESPACE=flux-system
POINTER=deploy
# The CLI of the Flux that install.sh puts in the cluster: the app version of its FLUX_CHART_VERSION, 2.10.0 for the
# chart 2.20.0.
FLUX_CLI_IMAGE=ghcr.io/fluxcd/flux-cli:v2.10.0@sha256:1a5178b1770052d6e88ab1c7c8434c5ac9bca8a8ab984dcaf6b9df31814ce9f2
MEMORY=64Mi
ACCOUNT=deploy-window
OPENS="${WINDOW_OPENS:-10 * * * *}"
CLOSES="${WINDOW_CLOSES:-20 * * * *}"
# A run missed for longer, as after the node was down, is skipped. Shorter than the window and than the time it stays
# closed, so of the two missed runs at most one starts and the Kustomization is left as the later schedule says.
START_DEADLINE_SECONDS="${WINDOW_START_DEADLINE_SECONDS:-300}"
SUSPENDED=true
RESUMED=false

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
  startingDeadlineSeconds: $START_DEADLINE_SECONDS
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
              command: [sh, -euc]
              # flux exits 0 also when it left the Kustomization as it was, so the job reads the state it was to leave.
              args:
                - |
                  flux $3 kustomization $POINTER --namespace $FLUX_NAMESPACE
                  suspended=\$(kubectl --namespace $FLUX_NAMESPACE get kustomization $POINTER \\
                    -o jsonpath='{.spec.suspend}')
                  if [ "\${suspended:-$RESUMED}" != $4 ]; then
                    echo "after flux $3 the Kustomization $POINTER has suspend '\$suspended', not $4" >&2
                    exit 1
                  fi
              securityContext:
                allowPrivilegeEscalation: false
                capabilities: {drop: [ALL]}
              resources:
                requests: {memory: $MEMORY}
                limits: {memory: $MEMORY}
EOF
}
window_job open "$OPENS" resume "$RESUMED"
window_job close "$CLOSES" suspend "$SUSPENDED"
