#!/usr/bin/env bash
set -euo pipefail

smoke_project="deskseed-smoke-$$"

cleanup() {
  docker compose --project-name "$smoke_project" down --volumes --remove-orphans
}

trap cleanup EXIT
docker compose --project-name "$smoke_project" up --build --detach

for _attempt in $(seq 1 30); do
  if curl --fail --silent --show-error http://localhost:8080/actuator/health >/dev/null; then
    exit 0
  fi
  sleep 2
done

docker compose --project-name "$smoke_project" logs --no-color
echo "Backend health endpoint did not return HTTP 200 within 60 seconds." >&2
exit 1
