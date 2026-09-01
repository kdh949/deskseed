#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v docker >/dev/null 2>&1 || {
  printf 'Required command is unavailable: docker\n' >&2
  exit 127
}

test_root="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-production-compose.XXXXXX")"
chmod 700 "$test_root"
cleanup() {
  local exit_code=$?
  trap - EXIT
  if [[ "$test_root" == "${TMPDIR:-/tmp}"/deskseed-production-compose.?????? ]]; then
    [[ ! -e "$test_root/merged.json" ]] || unlink "$test_root/merged.json"
    rmdir "$test_root" || exit_code=1
  else
    printf 'Refusing unexpected test directory cleanup: %s\n' "$test_root" >&2
    exit_code=1
  fi
  exit "$exit_code"
}
trap cleanup EXIT

DESKSEED_FRONTEND_BIND_ADDRESS=192.0.2.10 \
DESKSEED_FRONTEND_ORIGIN_PORT=18080 \
  docker compose \
    --project-name deskseed-production-contract \
    --file "$repository_root/compose.yaml" \
    --file "$repository_root/compose.production.yaml" \
    config --format json >"$test_root/merged.json"

python3 - "$test_root/merged.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    model = json.load(source)

services = model["services"]
assert set(services) == {"backend", "db", "frontend", "redis"}, services.keys()

frontend_ports = services["frontend"].get("ports", [])
assert len(frontend_ports) == 1, frontend_ports
assert frontend_ports[0]["host_ip"] == "192.0.2.10", frontend_ports
assert int(frontend_ports[0]["published"]) == 18080, frontend_ports
assert int(frontend_ports[0]["target"]) == 80, frontend_ports

for service in ("backend", "db", "redis"):
    assert not services[service].get("ports"), (service, services[service].get("ports"))

assert set(services["frontend"]["networks"]) == {"application"}
assert set(services["backend"]["networks"]) == {"application", "database", "customer-auth-limiter"}
assert set(services["db"]["networks"]) == {"database"}
assert set(services["redis"]["networks"]) == {"customer-auth-limiter"}

backend_environment = services["backend"]["environment"]
assert backend_environment["DESKSEED_MAIL_DELIVERY_ENABLED"] == "false"
assert backend_environment["DESKSEED_MAIL_TRANSPORT"] == "disabled"
PY

if docker compose \
  --project-name deskseed-production-contract \
  --file "$repository_root/compose.yaml" \
  --file "$repository_root/compose.production.yaml" \
  config --quiet >/dev/null 2>&1; then
  printf 'Expected missing production bind configuration to fail.\n' >&2
  exit 1
fi

printf 'Production Compose contract passed.\n'
