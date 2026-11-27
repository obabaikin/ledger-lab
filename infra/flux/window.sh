#!/bin/sh
# The deploy window: the hours' minutes in which a new pointer may start a deploy. Outside it the Kustomization that
# applies the pointer is suspended; a chain of releases that has started is not held by it and runs to its end.
# Prints the manifest of the two CronJobs that open and close the window and of their rights; install.sh applies it.
# WINDOW_OPENS and WINDOW_CLOSES take the place of the two schedules (cron, UTC); moved schedules need
# WINDOW_START_DEADLINE_SECONDS and WINDOW_ACTIVE_DEADLINE_SECONDS that together stay below the window's length and
# below the time it stays closed.
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
# A job, its retries included, ends this long after it was created, also when its pod never started, as while the
# image is pulled. With the start deadline it stays below the window and the time it stays closed, so a late job
# never acts after the job of the other kind; the spare minute holds the 30 s a stopped pod is given to end.
ACTIVE_DEADLINE_SECONDS="${WINDOW_ACTIVE_DEADLINE_SECONDS:-240}"
SUSPENDED=true
RESUMED=false
# flux resume waits for the reconciliation, 5 minutes by default, and fails when the wait runs out; its retry then
# opened a window that had closed meanwhile. The patch removes the pause and returns.
RESUME="kubectl --namespace $FLUX_NAMESPACE patch kustomization $POINTER --type merge \\
                    --patch '{\"spec\":{\"suspend\":$RESUMED}}'"
SUSPEND="flux suspend kustomization $POINTER --namespace $FLUX_NAMESPACE"

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
  # flux suspend lists before it patches, and a list cannot be narrowed to one name.
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
      activeDeadlineSeconds: $ACTIVE_DEADLINE_SECONDS
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
              # The command exits 0 also when it left the Kustomization as it was, so the job reads the state it was to
              # leave.
              args:
                - |
                  $3
                  suspended=\$(kubectl --namespace $FLUX_NAMESPACE get kustomization $POINTER \\
                    -o jsonpath='{.spec.suspend}')
                  if [ "\${suspended:-$RESUMED}" != $4 ]; then
                    echo "$ACCOUNT-$1 left the Kustomization $POINTER with suspend '\$suspended', not $4" >&2
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
window_job open "$OPENS" "$RESUME" "$RESUMED"
window_job close "$CLOSES" "$SUSPEND" "$SUSPENDED"
