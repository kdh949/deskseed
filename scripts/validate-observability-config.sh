#!/usr/bin/env sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

export DESKSEED_APP_BIND_ADDRESS=127.0.0.1
export DESKSEED_OBSERVABILITY_BIND_ADDRESS=127.0.0.1
export DESKSEED_LOKI_PUSH_URL=http://monitoring.internal:3100/loki/api/v1/push
export DESKSEED_LOKI_OTLP_HTTP_ENDPOINT=http://monitoring.internal:3100/otlp
export DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT=http://monitoring.internal:4318
export DESKSEED_PYROSCOPE_SERVER_URL=http://monitoring.internal:4040
export DESKSEED_SERVICE_VERSION=0123456789abcdef0123456789abcdef01234567
export DESKSEED_POSTGRES_EXPORTER_PGPASS_FILE=/dev/null

docker compose \
  -p deskseed-load \
  -f "$repository_root/compose.yaml" \
  -f "$repository_root/compose.observability.yaml" \
  --profile observability \
  config --quiet

docker run --rm \
  -e DESKSEED_COMPOSE_PROJECT_REGEX=deskseed-load \
  -e DESKSEED_LOKI_PUSH_URL="$DESKSEED_LOKI_PUSH_URL" \
  -e DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT="$DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT" \
  -v "$repository_root/ops/observability/alloy/config.alloy:/etc/alloy/config.alloy:ro" \
  grafana/alloy:v1.18.0 validate /etc/alloy/config.alloy

docker run --rm \
  -e DESKSEED_LOKI_OTLP_HTTP_ENDPOINT="$DESKSEED_LOKI_OTLP_HTTP_ENDPOINT" \
  -e DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT="$DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT" \
  -v "$repository_root/ops/observability/personal-staging/alloy/config.alloy:/etc/alloy/config.alloy:ro" \
  grafana/alloy:v1.18.0 validate /etc/alloy/config.alloy

docker run --rm \
  --entrypoint promtool \
  -v "$repository_root/ops/observability/monitoring-server:/etc/prometheus:ro" \
  prom/prometheus:v3.14.0 \
  check config /etc/prometheus/prometheus.yml.example

docker run --rm \
  --entrypoint promtool \
  -v "$repository_root/ops/observability/personal-staging:/etc/prometheus:ro" \
  prom/prometheus:v3.14.0 \
  check config /etc/prometheus/prometheus.yml.example

docker run --rm \
  --entrypoint promtool \
  -v "$repository_root/ops/observability/personal-staging:/etc/prometheus:ro" \
  prom/prometheus:v3.14.0 \
  check rules /etc/prometheus/deskseed-personal-staging.rules.yml

docker run --rm \
  --add-host backend:127.0.0.1 \
  -v "$repository_root/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.31-alpine nginx -t

docker run --rm \
  --add-host backend:127.0.0.1 \
  -v "$repository_root/frontend/nginx.personal-staging-observability.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.31-alpine nginx -t

jq empty "$repository_root/ops/observability/monitoring-server/grafana/deskseed-load-overview.json"
jq empty "$repository_root/ops/observability/personal-staging/grafana/deskseed-personal-staging-overview.json"
jq empty "$repository_root/ops/observability/personal-staging/search-diagnostics/grafana-dashboard.json"
jq empty "$repository_root/ops/observability/personal-staging/search-diagnostics/tempo-datasource.json"
jq empty "$repository_root/ops/observability/personal-staging/search-diagnostics/pyroscope-datasource.json"
jq -e '
  [.panels[] | select(.title == "Recent backend logs") | .targets[].expr] ==
    ["{service_name=\"deskseed-backend\"} | deployment_environment_name = \"personal-staging\""]
' "$repository_root/ops/observability/personal-staging/grafana/deskseed-personal-staging-overview.json" >/dev/null

bash "$repository_root/scripts/test-performance-rca-observability-contract.sh"

promql_rules=$(mktemp "${TMPDIR:-/tmp}/deskseed-dashboard-promql.XXXXXX.yml")
trap 'rm -f "$promql_rules"' EXIT HUP INT TERM
{
  printf 'groups:\n  - name: deskseed-dashboard-promql-contract\n    rules:\n'
  jq -s -r '
    [.[].panels[] | select(.datasource.type == "prometheus") | .targets[]?.expr] |
    to_entries[] |
    "      - record: deskseed_dashboard_expr_\(.key)\n        expr: |\n          " +
      (.value
        | gsub("\\$environment"; "personal-staging")
        | gsub("\\$run"; ".*")
        | gsub("\\$__rate_interval"; "5m")
        | gsub("\\$__range"; "30m")
        | gsub("\n"; "\n          "))
  ' "$repository_root/ops/observability/personal-staging/grafana/deskseed-personal-staging-overview.json" \
    "$repository_root/ops/observability/personal-staging/search-diagnostics/grafana-dashboard.json"
} >"$promql_rules"
docker run --rm \
  --entrypoint promtool \
  -v "$promql_rules:/etc/prometheus/dashboard-promql.yml:ro" \
  prom/prometheus:v3.14.0 \
  check rules /etc/prometheus/dashboard-promql.yml
rm -f "$promql_rules"
trap - EXIT HUP INT TERM

for scenario in agent-read public-request customer-auth-limiter collaboration-websocket; do
  docker run --rm \
    -v "$repository_root/tests/load:/scripts:ro" \
    grafana/k6:2.0.0 inspect \
    -e TARGET_URL=http://deskseed.invalid \
    -e CONFIRM_DESTRUCTIVE_WRITES=true \
    "/scripts/scenarios/$scenario.js" >/dev/null
done
