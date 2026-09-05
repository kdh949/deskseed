#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-production-runtime.XXXXXX")"
project_suffix="${test_root##*.}"
project_name="deskseed-production-runtime-$(printf '%s' "$project_suffix" | tr '[:upper:]' '[:lower:]')"
overlay_file="$test_root/live.yaml"
acl_file="$test_root/redis.acl"
admin_password_file="$test_root/first-admin-password"

cleanup() {
  local exit_code=$?
  trap - EXIT
  docker compose --project-name "$project_name" \
    --file "$repository_root/compose.yaml" \
    --file "$repository_root/compose.production.yaml" \
    --file "$overlay_file" down --volumes --remove-orphans >/dev/null 2>&1 || exit_code=1
  if [[ "$test_root" == "${TMPDIR:-/tmp}"/deskseed-production-runtime.?????? ]]; then
    [[ ! -e "$overlay_file" ]] || unlink "$overlay_file"
    [[ ! -e "$acl_file" ]] || unlink "$acl_file"
    [[ ! -e "$admin_password_file" ]] || unlink "$admin_password_file"
    rmdir "$test_root" || exit_code=1
  else
    printf 'Refusing unexpected runtime-test cleanup: %s\n' "$test_root" >&2
    exit_code=1
  fi
  exit "$exit_code"
}
trap cleanup EXIT

backend_port="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"
cat >"$overlay_file" <<YAML
services:
  backend:
    environment:
      # PR #154 intentionally precedes the production attachment adapter. This
      # test-only profile keeps the local adapter while exercising the exact
      # production DB/Redis services and Spring aggregate health indicator.
      SPRING_PROFILES_ACTIVE: runtime-live-test
      DATABASE_USERNAME: deskseed_runtime
      DATABASE_PASSWORD: runtime-test-application-password
      SPRING_DATA_REDIS_USERNAME: deskseed
      SPRING_DATA_REDIS_PASSWORD: runtime-test-redis-password
      SPRING_DATA_REDIS_SSL_ENABLED: "false"
      DESKSEED_ACCESS_AUDIT_ACTIVE_KEY_VERSION: local-v1
      DESKSEED_ACCESS_AUDIT_KEY_LOCAL_V1: AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
      DESKSEED_MAIL_PROTECTED_ACTIVE_KEY_VERSION: local-v1
      DESKSEED_MAIL_PROTECTED_KEY_LOCAL_V1: AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
      DESKSEED_MAIL_OPERATIONS_CURSOR_ACTIVE_KEY_ID: local-v1
    ports: !override
      - "127.0.0.1:${backend_port}:8080"
YAML
printf '%s' 'unused-disabled-bootstrap-secret' >"$admin_password_file"

export POSTGRES_DB=deskseed_runtime_test
export DATABASE_BOOTSTRAP_USERNAME=deskseed_bootstrap
export DATABASE_BOOTSTRAP_PASSWORD=runtime-test-bootstrap-password
export DATABASE_MIGRATION_USERNAME=deskseed_migration
export DATABASE_MIGRATION_PASSWORD=runtime-test-migration-password
export DATABASE_RUNTIME_USERNAME=deskseed_runtime
export DATABASE_RUNTIME_PASSWORD=runtime-test-application-password
export DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD=runtime-test-redis-password
export DESKSEED_REDIS_ACL_FILE="$acl_file"
export DESKSEED_RUNTIME_USER="$(id -u):$(id -g)"
export DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE="$admin_password_file"
export DESKSEED_FRONTEND_BIND_ADDRESS=127.0.0.1
export DESKSEED_FRONTEND_ORIGIN_PORT=18080

test_b64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
export DESKSEED_PLATFORM_ALLOWED_CLIENT_CIDRS=192.0.2.0/24
export DESKSEED_PLATFORM_TRUSTED_PROXY_CIDRS=172.30.10.0/24
export DESKSEED_WEBHOOK_SECRET_KEY_V1="$test_b64"
export DESKSEED_MAIL_PROTECTED_KEY_V1="$test_b64"
export DESKSEED_MAIL_OPERATIONS_CURSOR_SIGNING_KEY=runtime-test-mail-cursor-signing-key-change-me
export DESKSEED_CUSTOMER_AUTH_FINGERPRINT_KEY="$test_b64"
export DESKSEED_CUSTOMER_AUTH_CSRF_KEY="$test_b64"
export DESKSEED_CUSTOMER_AUTH_TRUSTED_PROXY_CIDRS=172.30.10.0/24
export DESKSEED_CUSTOMER_MAGIC_LINK_CONSUME_URL=https://support.example.test/customer/sign-in/consume
export DESKSEED_CUSTOMER_REGISTRATION_VERIFICATION_URL=https://support.example.test/customer/register/verify
export DESKSEED_CUSTOMER_PASSWORD_RESET_URL=https://support.example.test/customer/password/reset
export DESKSEED_PUBLIC_REQUEST_RATE_LIMIT_FINGERPRINT_KEY="$test_b64"
export DESKSEED_PUBLIC_REQUEST_RATE_LIMIT_TRUSTED_PROXY_CIDRS=172.30.10.0/24
export DESKSEED_CUSTOMER_CLAIM_SIGNING_KEY=BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=
export DESKSEED_CUSTOMER_CLAIM_FINGERPRINT_KEY=BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=
export DESKSEED_CUSTOMER_REQUEST_CURSOR_SIGNING_KEY=BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY=
export DESKSEED_ACCESS_AUDIT_SESSION_FINGERPRINT_KEY="$test_b64"
export DESKSEED_ACCESS_AUDIT_KEY_V1="$test_b64"
export DESKSEED_AUDIT_CURSOR_SIGNING_KEY=runtime-test-audit-cursor-signing-key-change-me
export DESKSEED_CORS_ALLOWED_ORIGINS=https://support.example.test
export DESKSEED_AGENT_TICKET_CURSOR_SIGNING_KEY=runtime-test-agent-cursor-signing-key-change-me

# These become required in the stacked attachment slice and are harmless here.
export DESKSEED_VERSITY_ACCESS_KEY=runtime-test-versity-access
export DESKSEED_VERSITY_SECRET_KEY=runtime-test-versity-secret-change-me
export DESKSEED_ATTACHMENT_UPSTREAM_WAF_ACKNOWLEDGED=true

DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD="$DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD" \
  "$repository_root/scripts/production/render-redis-acl.sh" "$acl_file"

compose=(docker compose --project-name "$project_name"
  --file "$repository_root/compose.yaml"
  --file "$repository_root/compose.production.yaml"
  --file "$overlay_file")

if ! "${compose[@]}" up --build --detach backend; then
  "${compose[@]}" logs --no-color db db-migrate redis
  exit 1
fi

health_ready=false
for _attempt in $(seq 1 90); do
  if curl --fail --silent --show-error "http://127.0.0.1:$backend_port/actuator/health" >/dev/null 2>&1; then
    health_ready=true
    break
  fi
  if [[ -z "$("${compose[@]}" ps --status running --quiet backend)" ]]; then
    break
  fi
  sleep 2
done
if [[ "$health_ready" != true ]]; then
  "${compose[@]}" logs --no-color backend redis db-permissions
  exit 1
fi
curl --fail --silent --show-error "http://127.0.0.1:$backend_port/actuator/health" \
  | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'

"${compose[@]}" exec -T redis test -r /run/deskseed-redis/users.acl
"${compose[@]}" exec -T -e REDISCLI_AUTH="$DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD" redis \
  redis-cli --user deskseed info server >/dev/null

role_flags="$("${compose[@]}" exec -T -e PGPASSWORD="$DATABASE_BOOTSTRAP_PASSWORD" db \
  psql --username "$DATABASE_BOOTSTRAP_USERNAME" --dbname "$POSTGRES_DB" --tuples-only --no-align \
  --command "select concat(rolsuper, ':', rolcreatedb, ':', rolcreaterole) from pg_roles where rolname = '$DATABASE_MIGRATION_USERNAME'" \
  | tr -d '[:space:]')"
[[ "$role_flags" == "f:f:f" ]] || {
  printf 'Migration role has unsafe flags: %s\n' "$role_flags" >&2
  exit 1
}

printf 'Production runtime live health passed.\n'
