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

if docker compose \
  --project-name deskseed-production-contract \
  --file "$repository_root/compose.yaml" \
  --file "$repository_root/compose.production.yaml" \
  config --quiet >/dev/null 2>&1; then
  printf 'Expected missing production bind configuration to fail.\n' >&2
  exit 1
fi

printf 'Production Compose contract passed.\n'
