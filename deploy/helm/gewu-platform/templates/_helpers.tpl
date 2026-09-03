{{/* 格物平台 Helm 公共模板 */}}
{{- define "gewu.name" -}}
{{- .Release.Name -}}-gewu
{{- end -}}

{{- define "gewu.labels" -}}
app.kubernetes.io/name: gewu-platform
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: gewu-platform
{{- end -}}

{{- define "gewu.envFrom" -}}
envFrom:
  - configMapRef:
      name: {{ include "gewu.name" . }}-config
  - secretRef:
      name: {{ include "gewu.name" . }}-secret
{{- end -}}
