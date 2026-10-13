{{- define "ledger-lab.image" -}}
{{ .root.Values.image.repository }}/{{ .service.module }}:{{ .root.Values.image.tag }}
{{- end }}

{{- define "ledger-lab.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: ledger-lab
{{- end }}

{{- define "ledger-lab.scrape" -}}
prometheus.io/scrape: "true"
prometheus.io/port: {{ .Values.managementPort | quote }}
prometheus.io/path: /actuator/prometheus
{{- end }}
