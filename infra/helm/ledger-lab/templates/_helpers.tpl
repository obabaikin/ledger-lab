{{- define "ledger-lab.image" -}}
{{ .root.Values.image.repository }}/{{ .service.module }}:{{ .root.Values.image.tag }}
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
The bank's numbers, checked together; every template that reads one of them includes this first.
control refuses to start unless its own load.default-tps and load.tps-step (control/src/main/resources/application.yaml)
fit the speeds set here; the chart says so before the deploy instead of a control pod that never gets ready.
*/}}
{{- define "ledger-lab.checkNumbers" -}}
{{- $period := include "ledger-lab.whole" (list "periodMinutes" .Values.periodMinutes false) | int64 }}
{{- $retention := include "ledger-lab.whole" (list "retentionHours" .Values.retentionHours false) | int64 }}
{{- include "ledger-lab.whole" (list "accounts.count" .Values.accounts.count false) | int64 }}
{{- $base := include "ledger-lab.whole" (list "load.baseTps" .Values.load.baseTps false) | int64 }}
{{- $max := include "ledger-lab.whole" (list "load.maxTps" .Values.load.maxTps false) | int64 }}
{{- include "ledger-lab.whole" (list "maintenance.noticeHours" .Values.maintenance.noticeHours true) | int64 }}
{{- if lt (mul $retention 60) $period }}
{{- fail "retentionHours must hold at least one period of periodMinutes" }}
{{- end }}
{{- $controlDefaultTps := 1000 }}{{ $controlTpsStep := 100 }}
{{- if or (gt $base $controlDefaultTps) (lt $max $controlDefaultTps) }}
{{- fail (printf "load.baseTps and load.maxTps must hold control's default pulse of %d TPS" $controlDefaultTps) }}
{{- end }}
{{- if mod (sub $max $base) $controlTpsStep }}
{{- fail (printf "load.maxTps - load.baseTps must be a multiple of control's step of %d TPS" $controlTpsStep) }}
{{- end }}
{{- if mod (sub $controlDefaultTps $base) $controlTpsStep }}
{{- fail (printf "control's default pulse of %d TPS must be load.baseTps plus a multiple of its step of %d TPS" $controlDefaultTps $controlTpsStep) }}
{{- end }}
{{- end }}
