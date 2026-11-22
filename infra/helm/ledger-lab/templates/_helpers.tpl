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

{{- define "ledger-lab.restricted" -}}
allowPrivilegeEscalation: false
capabilities: {drop: [ALL]}
{{- end }}

{{- /*
A number of the values file as a whole number, or no chart at all: Helm reads every number there as a float, so 1000000
would reach a service as "1e+06" and 0.5 hours would be cut to 0. Takes (list "name" value allowZero).
*/}}
{{- define "ledger-lab.whole" -}}
{{- $name := index . 0 }}{{ $value := index . 1 }}{{ $allowZero := index . 2 }}
{{- if not (or (kindIs "float64" $value) (kindIs "int64" $value) (kindIs "int" $value)) }}
{{- fail (printf "%s must be a number, not %v" $name $value) }}
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
{{- $period := include "ledger-lab.whole" (list "periodMinutes" .Values.periodMinutes false) | int64 }}
{{- $retention := include "ledger-lab.whole" (list "retentionHours" .Values.retentionHours false) | int64 }}
{{- include "ledger-lab.whole" (list "accounts.count" .Values.accounts.count false) | int64 }}
{{- $base := include "ledger-lab.whole" (list "load.baseTps" .Values.load.baseTps false) | int64 }}
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
{{- if mod (sub $max $base) $step }}
{{- fail "load.maxTps - load.baseTps must be a multiple of load.tpsStep" }}
{{- end }}
{{- if or (lt $pulse $base) (gt $pulse $max) (mod (sub $pulse $base) $step) }}
{{- fail "load.defaultPulseTps must be one of the options from load.baseTps to load.maxTps in steps of load.tpsStep" }}
{{- end }}
{{- end }}
