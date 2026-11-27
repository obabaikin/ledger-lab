{{- define "ledger-lab.image" -}}
{{ .root.Values.image.repository }}/{{ .service.module }}:{{ .service.tag | default .root.Values.image.tag }}
{{- end }}

{{- define "ledger-lab.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: ledger-lab
{{- end }}

{{- define "ledger-lab.scrape" -}}
prometheus.io/scrape: "true"
prometheus.io/path: /actuator/prometheus
{{- end }}

{{- define "ledger-lab.prometheusUrl" -}}
http://prometheus:9090
{{- end }}

{{- /* The metric kube-state-metrics gives every condition of a HelmRelease, and the rules read. */}}
{{- define "ledger-lab.releaseCondition" -}}
flux_helmrelease_condition
{{- end }}

{{- /* The label of the alerts that wait for a hand, mailed with the highest importance. */}}
{{- define "ledger-lab.urgentSeverity" -}}
critical
{{- end }}

{{- /*
A memory size of the values file in bytes, for a rule that compares it with a measured one. Takes "<n>Mi" or "<n>Gi".
*/}}
{{- define "ledger-lab.bytes" -}}
{{- if not (regexMatch "^[0-9]+(Mi|Gi)$" (toString .)) }}
{{- fail (printf "a memory limit must be a whole number of Mi or Gi, not %v" .) }}
{{- end }}
{{- if hasSuffix "Mi" . }}{{ mul (trimSuffix "Mi" . | int64) 1048576 }}{{ else }}{{ mul (trimSuffix "Gi" . | int64) 1073741824 }}{{ end }}
{{- end }}

{{- define "ledger-lab.restricted" -}}
allowPrivilegeEscalation: false
capabilities: {drop: [ALL]}
{{- end }}

{{- /*
A number of the values file as a whole number, or no chart at all: Helm reads every number there as a float, so 1000000
would reach a service as "1e+06" and 0.5 hours would be cut to 0. Takes (list "name" value allowZero [max]); the
highest value is compared before the number is cut to a whole one, where a float beyond int64 would wrap.
*/}}
{{- define "ledger-lab.whole" -}}
{{- $name := index . 0 }}{{ $value := index . 1 }}{{ $allowZero := index . 2 }}
{{- if not (or (kindIs "float64" $value) (kindIs "int64" $value) (kindIs "int" $value)) }}
{{- fail (printf "%s must be a number, not %v" $name $value) }}
{{- end }}
{{- if and (gt (len .) 3) (gt (float64 $value) (float64 (index . 3))) }}
{{- fail (printf "%s must be at most %d, not %v" $name (int64 (index . 3)) $value) }}
{{- end }}
{{- if ne (float64 $value) (float64 (int64 $value)) }}
{{- fail (printf "%s must be a whole number, not %v" $name $value) }}
{{- end }}
{{- if or (lt (int64 $value) 0) (and (eq (int64 $value) 0) (not $allowZero)) }}
{{- fail (printf "%s must be above zero, not %v" $name $value) }}
{{- end }}
{{- int64 $value }}
{{- end }}

{{- /*
The bank's numbers, checked together; every template that reads one of them includes this first. control refuses to start
with load options that do not fit, so the chart says so before the deploy instead of a control pod that never gets ready.
*/}}
{{- define "ledger-lab.checkNumbers" -}}
{{- $javaIntMax := 2147483647 }}
{{- $hoursInYear := 8760 }}
{{- $period := include "ledger-lab.whole" (list "periodMinutes" .Values.periodMinutes false) | int64 }}
{{- $retention := include "ledger-lab.whole" (list "retentionHours" .Values.retentionHours false $hoursInYear) | int64 }}
{{- include "ledger-lab.whole" (list "accounts.count" .Values.accounts.count false $javaIntMax) | int64 }}
{{- $base := include "ledger-lab.whole" (list "load.baseTps" .Values.load.baseTps false) | int64 }}
{{- $min := include "ledger-lab.whole" (list "load.minPulseTps" .Values.load.minPulseTps false) | int64 }}
{{- $max := include "ledger-lab.whole" (list "load.maxTps" .Values.load.maxTps false) | int64 }}
{{- $step := include "ledger-lab.whole" (list "load.tpsStep" .Values.load.tpsStep false) | int64 }}
{{- $pulse := include "ledger-lab.whole" (list "load.defaultPulseTps" .Values.load.defaultPulseTps false) | int64 }}
{{- include "ledger-lab.whole" (list "maintenance.noticeHours" .Values.maintenance.noticeHours true) | int64 }}
{{- if lt (mul $retention 60) $period }}
{{- fail "retentionHours must hold at least one period of periodMinutes" }}
{{- end }}
{{- if gt $base $max }}
{{- fail "load.baseTps must not be above load.maxTps" }}
{{- end }}
{{- if or (gt $min $max) (mod (sub $max $min) $step) }}
{{- fail "load.maxTps - load.minPulseTps must be a multiple of load.tpsStep, at least zero" }}
{{- end }}
{{- if or (lt $pulse $min) (gt $pulse $max) (mod (sub $pulse $min) $step) }}
{{- fail "load.defaultPulseTps must be one of the options from load.minPulseTps to load.maxTps in steps of load.tpsStep" }}
{{- end }}
{{- end }}

{{- /*
A setup Job named by a hash of its spec and of what else it reads (a script, a password): an upgrade that changes none
of them leaves the finished Job as it is, a change replaces it. It is kept after it finishes: Helm creates an object of
its manifest that the cluster lacks, so a Job deleted after its time would run again on every upgrade. For the same
reason a failed one holds every later release, which waits for Jobs, until it is deleted by hand and so runs again; and
a wiped volume does not run it again unless it is deleted with the volume. Takes (dict "name" "spec" "inputs").
*/}}
{{- define "ledger-lab.setupJob" -}}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ .name }}-{{ print .spec .inputs | sha256sum | trunc 10 }}
  labels: {{- include "ledger-lab.labels" (dict "name" .name) | nindent 4 }}
spec: {{- .spec | nindent 2 }}
{{- end }}

{{- /*
Whether a template's objects belong to the release being rendered: each service is a release of its own, so a deploy
replaces one service at a time; "platform" holds what they share. An empty component renders all of it, as
one release. Takes (list $root "platform" | "<service>"); "true" or "".
*/}}
{{- define "ledger-lab.renders" -}}
{{- $root := index . 0 }}{{ $part := index . 1 }}{{ $component := $root.Values.component | default "" }}
{{- if not (or (eq $component "") (eq $component "platform") (hasKey $root.Values.services $component)) }}
{{- fail (printf "component must be empty, platform or one of %s, not %s" (keys $root.Values.services | sortAlpha | join ", ") $component) }}
{{- end }}
{{- if or (eq $component "") (eq $component $part) }}true{{ end }}
{{- end }}
