#!/usr/bin/env sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

export DESKSEED_APP_BIND_ADDRESS=127.0.0.1
export DESKSEED_OBSERVABILITY_BIND_ADDRESS=127.0.0.1
export DESKSEED_LOKI_PUSH_URL=http://monitoring.internal:3100/loki/api/v1/push
export DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT=http://monitoring.internal:4318
export DESKSEED_PYROSCOPE_SERVER_URL=http://monitoring.internal:4040
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
  --entrypoint promtool \
  -v "$repository_root/ops/observability/monitoring-server:/etc/prometheus:ro" \
  prom/prometheus:v3.14.0 \
  check config /etc/prometheus/prometheus.yml.example

docker run --rm \
  --add-host backend:127.0.0.1 \
  -v "$repository_root/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.31-alpine nginx -t

jq empty "$repository_root/ops/observability/monitoring-server/grafana/deskseed-load-overview.json"

for scenario in agent-read public-request customer-auth-limiter collaboration-websocket; do
  docker run --rm \
    -v "$repository_root/tests/load:/scripts:ro" \
    grafana/k6:2.0.0 inspect \
    -e TARGET_URL=http://deskseed.invalid \
    -e CONFIRM_DESTRUCTIVE_WRITES=true \
    "/scripts/scenarios/$scenario.js" >/dev/null
done
