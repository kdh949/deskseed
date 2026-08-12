#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
container_name="deskseed-audit-perf-$$"

cleanup() {
  docker rm --force "$container_name" >/dev/null 2>&1 || true
}

trap cleanup EXIT

docker run --detach --rm \
  --name "$container_name" \
  --env POSTGRES_DB=deskseed_perf \
  --env POSTGRES_USER=deskseed \
  --env POSTGRES_PASSWORD=deskseed-perf-only \
  postgres:17-alpine >/dev/null

database_ready=false
for _attempt in $(seq 1 30); do
  if docker exec "$container_name" pg_isready -U deskseed -d deskseed_perf >/dev/null; then
    database_ready=true
    break
  fi
  sleep 1
done

if [[ "$database_ready" != "true" ]]; then
  docker logs "$container_name"
  echo "Audit Explorer performance database did not become ready." >&2
  exit 1
fi

docker exec --interactive "$container_name" \
  psql -U deskseed -d deskseed_perf \
  < "$repository_root/scripts/audit-explorer-performance.sql"
