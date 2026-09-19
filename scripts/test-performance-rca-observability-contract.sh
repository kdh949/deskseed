#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
live="$repository_root/ops/observability/personal-staging/grafana/deskseed-personal-staging-overview.json"
rca="$repository_root/ops/observability/personal-staging/search-diagnostics/grafana-dashboard.json"
tempo="$repository_root/ops/observability/personal-staging/search-diagnostics/tempo-datasource.json"
queries="$repository_root/ops/observability/personal-staging/search-diagnostics/postgres-queries.yaml"
collector_compose="$repository_root/ops/observability/personal-staging/search-diagnostics/compose.yaml"

jq -e '
  .uid == "deskseed-personal-staging-overview" and
  .title == "Deskseed Live Operations" and
  .refresh == "15s" and .time.from == "now-30m" and
  ([.panels[].id] | length == (unique | length)) and
  all(.panels[]; (.id | type) == "number" and (.description | length) > 0) and
  all(.panels[] | select(.type != "logs" and .type != "text");
    (.fieldConfig.defaults.unit | length) > 0 and (.fieldConfig.defaults.noValue | length) > 0 and
    (.fieldConfig.defaults.thresholds.steps | length) > 0)
' "$live" >/dev/null

jq -e '
  .uid == "deskseed-search-diagnostics" and
  .title == "Deskseed Performance RCA" and
  .refresh == "15s" and .time.from == "now-30m" and
  ([.panels[].id] | length == (unique | length)) and
  all(.panels[]; (.id | type) == "number" and (.description | length) > 0 and (.fieldConfig.defaults.noValue | length) > 0) and
  all(.panels[] | select(.type != "logs" and .type != "text");
    (.fieldConfig.defaults.unit | length) > 0 and (.fieldConfig.defaults.thresholds.steps | length) > 0) and
  any(.panels[]; .id == 22 and (.description | contains("Unattributed"))) and
  any(.panels[]; .id == 23 and .type == "traces") and
  any(.panels[]; .id == 24 and (.targets[0].expr | contains("queryid")))
' "$rca" >/dev/null

jq -e '
  .jsonData.tracesToProfiles.datasourceUid == "pyroscope" and
  .jsonData.tracesToProfiles.profileTypeId == "process_cpu:cpu:nanoseconds:cpu:nanoseconds" and
  .jsonData.tracesToLogsV2.datasourceUid == "loki"
' "$tempo" >/dev/null

grep -F "query_summary" "$queries" >/dev/null
grep -F "mean_execution_seconds" "$queries" >/dev/null
if grep -Eq 'PYROSCOPE_PROFILER_(ALLOC|LOCK):[[:space:]]*0([[:space:]]|$)' \
  "$repository_root/compose.observability.yaml" \
  "$repository_root/compose.personal-staging-diagnostics.yaml"; then
  printf 'Zero-valued allocation/lock profiling configuration is forbidden.\n' >&2
  exit 1
fi
if grep -Eq '(auto_explain|log_statement|log_parameter_max_length)' "$queries"; then
  printf 'Raw SQL logging or auto_explain is forbidden in the canonical collector.\n' >&2
  exit 1
fi
grep -F '${DIAGNOSTICS_BIND_ADDRESS:?set a private host IP}:9187:9187' "$collector_compose" >/dev/null
grep -F '${DIAGNOSTICS_BIND_ADDRESS:?set a private host IP}:9100:9100' "$collector_compose" >/dev/null
if grep -Eq '(^|["[:space:]])(0\.0\.0\.0|::):' "$collector_compose" \
  "$repository_root/compose.personal-staging-diagnostics.yaml"; then
  printf 'Wildcard diagnostics bind is forbidden.\n' >&2
  exit 1
fi
if grep -Eq '(^|[[:space:]])-[[:space:]]+query:' "$queries"; then
  printf 'Query text must not be declared as an exported PostgreSQL metric.\n' >&2
  exit 1
fi

printf 'Performance RCA observability contract passed.\n'
