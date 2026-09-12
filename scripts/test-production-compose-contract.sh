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
    for artifact in "$test_root/merged.json" "$test_root/personal-staging.json" \
      "$test_root/personal-staging-observability.json"; do
      [[ ! -e "$artifact" ]] || unlink "$artifact"
    done
    rmdir "$test_root" || exit_code=1
  else
    printf 'Refusing unexpected test directory cleanup: %s\n' "$test_root" >&2
    exit_code=1
  fi
  exit "$exit_code"
}
trap cleanup EXIT

export POSTGRES_DB=deskseed
export DATABASE_BOOTSTRAP_USERNAME=deskseed_bootstrap
export DATABASE_BOOTSTRAP_PASSWORD=contract-bootstrap-password
export DATABASE_MIGRATION_USERNAME=deskseed_migration
export DATABASE_MIGRATION_PASSWORD=contract-migration-password
export DATABASE_RUNTIME_USERNAME=deskseed_runtime
export DATABASE_RUNTIME_PASSWORD=contract-runtime-password
export DESKSEED_REDIS_ACL_FILE="$repository_root/config/production/redis.acl.example"
export DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD=contract-redis-password
export DESKSEED_CUSTOMER_AUTH_REDIS_PLAINTEXT_INTERNAL_NETWORK_ACK=true
export DESKSEED_VERSITY_ACCESS_KEY=contract-versity-access
export DESKSEED_VERSITY_SECRET_KEY=contract-versity-secret-key
export DESKSEED_ATTACHMENT_UPSTREAM_WAF_ACKNOWLEDGED=true
export DESKSEED_ATTACHMENT_S3_PLAINTEXT_INTERNAL_NETWORK_ACK=true
export DESKSEED_PLATFORM_ALLOWED_CLIENT_CIDRS=192.0.2.0/24
export DESKSEED_PLATFORM_TRUSTED_PROXY_CIDRS=172.30.10.0/24
export DESKSEED_WEBHOOK_SECRET_KEY_V1=contract-webhook-secret
export DESKSEED_MAIL_PROTECTED_KEY_V1=contract-mail-protected-key
export DESKSEED_MAIL_OPERATIONS_CURSOR_SIGNING_KEY=contract-mail-cursor-key
export DESKSEED_CUSTOMER_AUTH_FINGERPRINT_KEY=contract-customer-fingerprint-key
export DESKSEED_CUSTOMER_AUTH_CSRF_KEY=contract-customer-csrf-key
export DESKSEED_CUSTOMER_AUTH_TRUSTED_PROXY_CIDRS=172.30.10.0/24
export DESKSEED_CUSTOMER_MAGIC_LINK_CONSUME_URL=https://support.example.test/customer/sign-in/consume
export DESKSEED_CUSTOMER_REGISTRATION_VERIFICATION_URL=https://support.example.test/customer/register/verify
export DESKSEED_CUSTOMER_PASSWORD_RESET_URL=https://support.example.test/customer/password/reset
export DESKSEED_PUBLIC_REQUEST_RATE_LIMIT_FINGERPRINT_KEY=contract-public-rate-limit-key
export DESKSEED_PUBLIC_REQUEST_RATE_LIMIT_TRUSTED_PROXY_CIDRS=172.30.10.0/24
export DESKSEED_CUSTOMER_CLAIM_SIGNING_KEY=contract-customer-claim-key
export DESKSEED_CUSTOMER_CLAIM_FINGERPRINT_KEY=contract-customer-claim-fingerprint-key
export DESKSEED_CUSTOMER_REQUEST_CURSOR_SIGNING_KEY=contract-customer-request-cursor-key
export DESKSEED_ACCESS_AUDIT_SESSION_FINGERPRINT_KEY=contract-access-audit-fingerprint-key
export DESKSEED_ACCESS_AUDIT_KEY_V1=contract-access-audit-key
export DESKSEED_AUDIT_CURSOR_SIGNING_KEY=contract-audit-cursor-key
export DESKSEED_CORS_ALLOWED_ORIGINS=https://support.example.test
export DESKSEED_AGENT_TICKET_CURSOR_SIGNING_KEY=contract-agent-ticket-cursor-key

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
assert set(services) == {
    "backend", "db", "db-migrate", "db-permissions", "frontend", "redis", "versitygw"
}, services.keys()

frontend_ports = services["frontend"].get("ports", [])
assert len(frontend_ports) == 1, frontend_ports
assert frontend_ports[0]["host_ip"] == "192.0.2.10", frontend_ports
assert int(frontend_ports[0]["published"]) == 18080, frontend_ports
assert int(frontend_ports[0]["target"]) == 80, frontend_ports

for service in ("backend", "db", "db-migrate", "db-permissions", "redis", "versitygw"):
    assert not services[service].get("ports"), (service, services[service].get("ports"))

assert set(services["frontend"]["networks"]) == {"application"}
assert set(services["backend"]["networks"]) == {
    "application", "database", "customer-auth-limiter", "object-storage"
}
assert set(services["db"]["networks"]) == {"database"}
assert set(services["db-migrate"]["networks"]) == {"database"}
assert set(services["db-permissions"]["networks"]) == {"database"}
assert set(services["redis"]["networks"]) == {"customer-auth-limiter"}
assert set(services["versitygw"]["networks"]) == {"object-storage"}

backend_environment = services["backend"]["environment"]
assert backend_environment["SPRING_PROFILES_ACTIVE"] == "production"
assert backend_environment["SPRING_FLYWAY_ENABLED"] == "false"
assert backend_environment["DATABASE_RUNTIME_USERNAME"] == "deskseed_runtime"
assert "DATABASE_MIGRATION_USERNAME" not in backend_environment
assert "DATABASE_MIGRATION_PASSWORD" not in backend_environment
assert backend_environment["DESKSEED_CUSTOMER_AUTH_REDIS_HOST"] == "redis"
assert backend_environment["DESKSEED_CUSTOMER_AUTH_REDIS_USERNAME"] == "deskseed"
assert backend_environment["DESKSEED_CUSTOMER_AUTH_REDIS_TLS_ENABLED"] == "false"
assert backend_environment["DESKSEED_CUSTOMER_AUTH_REDIS_PLAINTEXT_INTERNAL_NETWORK_ACK"] == "true"
assert backend_environment["DESKSEED_ATTACHMENT_SCAN_MODE"] == "UPSTREAM_WAF"
assert backend_environment["DESKSEED_ATTACHMENT_UPSTREAM_WAF_ACKNOWLEDGED"] == "true"
assert backend_environment["DESKSEED_ATTACHMENT_S3_ENDPOINT"] == "http://versitygw:7070"
assert backend_environment["DESKSEED_ATTACHMENT_S3_ACCESS_KEY"] == "contract-versity-access"
assert backend_environment["DESKSEED_ATTACHMENT_S3_CREATE_BUCKET"] == "true"
assert backend_environment["DESKSEED_ATTACHMENT_S3_PLAINTEXT_INTERNAL_NETWORK_ACK"] == "true"
assert backend_environment["DESKSEED_MAIL_DELIVERY_ENABLED"] == "false"
assert backend_environment["DESKSEED_MAIL_TRANSPORT"] == "disabled"
assert backend_environment["SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE"] == "20971520B"
assert backend_environment["SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE"] == "110100480B"
file_limit = int(backend_environment["SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE"].removesuffix("B"))
request_limit = int(backend_environment["SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE"].removesuffix("B"))
assert request_limit >= (5 * file_limit) + (1024 * 1024)
assert backend_environment["MANAGEMENT_HEALTH_MAIL_ENABLED"] == "false"
assert "DESKSEED_ACCESS_AUDIT_KEY_LOCAL_V1" not in backend_environment
assert "DESKSEED_MAIL_PROTECTED_KEY_LOCAL_V1" not in backend_environment

redis_command = services["redis"]["command"]
assert "/run/deskseed-redis/users.acl" in redis_command, redis_command
assert services["redis"]["entrypoint"] == ["/opt/deskseed/production/prepare-redis-acl.sh"]
assert any(str(mount).startswith("/run/deskseed-redis") for mount in services["redis"]["tmpfs"])
redis_secret = services["redis"]["secrets"][0]
assert redis_secret["source"] == "deskseed-redis-acl", redis_secret
assert redis_secret["target"].endswith("/deskseed-redis-acl"), redis_secret

assert services["db-migrate"]["environment"]["FLYWAY_USER"] == "deskseed_migration"
assert services["db"]["environment"]["POSTGRES_USER"] == "deskseed_bootstrap"
assert services["db"]["environment"]["DESKSEED_MIGRATION_ROLE"] == "deskseed_migration"
assert services["db"]["environment"]["DESKSEED_RUNTIME_ROLE"] == "deskseed_runtime"
assert services["db-permissions"]["environment"]["DATABASE_RUNTIME_USERNAME"] == "deskseed_runtime"
assert services["backend"]["depends_on"]["db-permissions"]["condition"] == "service_completed_successfully"
assert services["backend"]["depends_on"]["versitygw"]["condition"] == "service_healthy"
assert services["versitygw"]["environment"]["VGW_HEALTH"] == "/health"
PY

export IMAGE_TAG=125727bbd2194bcf0937a7eca452231ffc7a4bb1
DESKSEED_FRONTEND_BIND_ADDRESS=192.0.2.10 \
DESKSEED_FRONTEND_ORIGIN_PORT=18080 \
  docker compose \
    --project-name deskseed-personal-staging-contract \
    --file "$repository_root/compose.yaml" \
    --file "$repository_root/compose.production.yaml" \
    --file "$repository_root/compose.personal-staging.yaml" \
    config --format json >"$test_root/personal-staging.json"

python3 - "$test_root/personal-staging.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    model = json.load(source)

services = model["services"]
assert services["backend"]["image"] == (
    "ghcr.io/kdh949/deskseed-backend:125727bbd2194bcf0937a7eca452231ffc7a4bb1"
)
assert services["frontend"]["image"] == (
    "ghcr.io/kdh949/deskseed-frontend:125727bbd2194bcf0937a7eca452231ffc7a4bb1"
)
assert "build" not in services["backend"], services["backend"].get("build")
assert "build" not in services["frontend"], services["frontend"].get("build")
assert "alloy" not in services, services.keys()
assert not services["backend"].get("ports"), services["backend"].get("ports")
assert "SPRING_PROFILES_ADDITIONAL" not in services["backend"]["environment"]
assert services["db-migrate"]["volumes"][0]["source"].endswith(
    "/backend/src/main/resources/db/migration"
)
assert services["db-permissions"]["volumes"][0]["source"].endswith(
    "/scripts/production"
)
PY

DESKSEED_FRONTEND_BIND_ADDRESS=192.0.2.10 \
DESKSEED_FRONTEND_ORIGIN_PORT=18080 \
DESKSEED_OBSERVABILITY_BIND_ADDRESS=192.0.2.11 \
DESKSEED_LOKI_OTLP_HTTP_ENDPOINT=https://loki.internal/otlp \
DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT=https://tempo.internal \
  docker compose \
    --project-name deskseed-personal-staging-observability-contract \
    --file "$repository_root/compose.yaml" \
    --file "$repository_root/compose.production.yaml" \
    --file "$repository_root/compose.personal-staging.yaml" \
    --file "$repository_root/compose.personal-staging-observability.yaml" \
    config --format json >"$test_root/personal-staging-observability.json"

python3 - "$test_root/personal-staging-observability.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    model = json.load(source)

services = model["services"]
assert set(services) == {
    "alloy", "backend", "db", "db-migrate", "db-permissions", "frontend", "redis", "versitygw"
}, services.keys()

backend = services["backend"]
backend_environment = backend["environment"]
assert backend_environment["SPRING_PROFILES_ACTIVE"] == "production"
assert backend_environment["SPRING_PROFILES_ADDITIONAL"] == "personal-staging-observability"
assert backend_environment["DESKSEED_PERSONAL_STAGING_OTLP_LOGS_ENDPOINT"] == "http://alloy:4318/v1/logs"
assert backend_environment["DESKSEED_PERSONAL_STAGING_OTLP_TRACES_ENDPOINT"] == "http://alloy:4318/v1/traces"
assert backend_environment["DESKSEED_PERSONAL_STAGING_TRACE_SAMPLING_PROBABILITY"] == "0.05"
backend_ports = backend["ports"]
assert len(backend_ports) == 1, backend_ports
assert backend_ports[0]["host_ip"] == "192.0.2.11", backend_ports
assert int(backend_ports[0]["published"]) == 9090, backend_ports
assert int(backend_ports[0]["target"]) == 9090, backend_ports
assert set(backend["networks"]) == {
    "application", "database", "customer-auth-limiter", "object-storage"
}

alloy = services["alloy"]
assert alloy["image"] == "grafana/alloy:v1.18.0"
assert alloy["user"] == "473:473"
assert alloy["environment"]["DESKSEED_LOKI_OTLP_HTTP_ENDPOINT"] == "https://loki.internal/otlp"
assert alloy["environment"]["DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT"] == "https://tempo.internal"
assert set(alloy["networks"]) == {"application"}
assert not alloy.get("privileged"), alloy
assert not alloy.get("devices"), alloy
assert not alloy.get("pid"), alloy
assert not alloy.get("network_mode"), alloy
assert alloy["read_only"] is True
assert "ALL" in alloy["cap_drop"]
assert "no-new-privileges:true" in alloy["security_opt"]
assert any(str(entry).startswith("/tmp") for entry in alloy["tmpfs"]), alloy["tmpfs"]

alloy_ports = alloy["ports"]
assert len(alloy_ports) == 1, alloy_ports
assert alloy_ports[0]["host_ip"] == "192.0.2.11", alloy_ports
assert int(alloy_ports[0]["published"]) == 12345, alloy_ports
assert int(alloy_ports[0]["target"]) == 12345, alloy_ports
assert set(map(str, alloy["expose"])) == {"4317", "4318"}

mounts = alloy["volumes"]
assert len(mounts) == 1, mounts
assert mounts[0]["source"].endswith("/ops/observability/personal-staging/alloy/config.alloy"), mounts
assert mounts[0]["target"] == "/etc/alloy/config.alloy", mounts
assert mounts[0]["read_only"] is True, mounts
assert "/var/run/docker.sock" not in json.dumps(alloy)
assert "/var/lib/docker" not in json.dumps(alloy)
assert "/rootfs" not in json.dumps(alloy)
PY

grep -Fx '    client_max_body_size 105m;' "$repository_root/frontend/nginx.conf" >/dev/null

if docker compose \
  --project-name deskseed-production-contract \
  --file "$repository_root/compose.yaml" \
  --file "$repository_root/compose.production.yaml" \
  config --quiet >/dev/null 2>&1; then
  printf 'Expected missing production bind configuration to fail.\n' >&2
  exit 1
fi

printf 'Production Compose contract passed.\n'
