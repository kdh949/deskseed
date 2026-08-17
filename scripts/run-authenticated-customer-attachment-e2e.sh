#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=e2e-compose-ownership.sh
source "$repository_root/scripts/e2e-compose-ownership.sh"

temporary_root="${TMPDIR:-/tmp}"
temporary_root="${temporary_root%/}"
work_dir="$(mktemp -d "$temporary_root/deskseed-customer-attachment-e2e.XXXXXX")"
chmod 700 "$work_dir"
password_file="$work_dir/first-admin-password"
bootstrap_password="$(LC_ALL=C od -An -N20 -tx1 /dev/urandom | tr -d '[:space:]')"

backend_port="${DESKSEED_CUSTOMER_ATTACHMENT_E2E_BACKEND_PORT:-18181}"
mailpit_port="${DESKSEED_CUSTOMER_ATTACHMENT_E2E_MAILPIT_PORT:-18126}"
frontend_port="${DESKSEED_CUSTOMER_ATTACHMENT_E2E_FRONTEND_PORT:-15175}"

cleanup() {
  local exit_code=$?
  local cleanup_failed=0
  trap - EXIT

  e2e_cleanup_owned_resources || cleanup_failed=1
  if [[ -f "$password_file" ]] && ! unlink "$password_file"; then
    cleanup_failed=1
  fi
  if [[ -n "$e2e_ownership_overlay_file" && -f "$e2e_ownership_overlay_file" ]] \
    && ! unlink "$e2e_ownership_overlay_file"; then
    cleanup_failed=1
  fi
  if [[ "$work_dir" == "$temporary_root"/deskseed-customer-attachment-e2e.?????? ]] \
    && [[ -d "$work_dir" ]] && ! rmdir "$work_dir"; then
    cleanup_failed=1
  fi
  [[ "$cleanup_failed" -eq 0 ]] || exit_code=1
  exit "$exit_code"
}
trap cleanup EXIT

for port in "$backend_port" "$mailpit_port" "$frontend_port"; do
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && ((port <= 65535)) || {
    printf 'Invalid authenticated customer attachment E2E port: %s\n' "$port" >&2
    exit 2
  }
done
python3 - "$backend_port" "$mailpit_port" "$frontend_port" <<'PY'
import socket
import sys

for raw_port in sys.argv[1:]:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.25)
        if probe.connect_ex(("127.0.0.1", int(raw_port))) == 0:
            raise SystemExit(f"Authenticated customer attachment E2E port is already in use: {raw_port}")
PY

[[ "$bootstrap_password" =~ ^[0-9a-f]{40}$ ]] || {
  printf 'Could not create an ephemeral authenticated customer attachment E2E password.\n' >&2
  exit 1
}
printf '%s' "$bootstrap_password" >"$password_file"
chmod 600 "$password_file"

export DESKSEED_BACKEND_PORT="$backend_port"
export DESKSEED_MAILPIT_PORT="$mailpit_port"
export DESKSEED_FRONTEND_PORT="$frontend_port"
export DESKSEED_RUNTIME_USER="$(id -u):$(id -g)"
export DESKSEED_CORS_ALLOWED_ORIGINS="http://localhost:$frontend_port"
export DESKSEED_MAIL_PUBLIC_BASE_URL="http://localhost:$frontend_port"
export DESKSEED_BOOTSTRAP_ADMIN_ENABLED=true
export DESKSEED_BOOTSTRAP_ADMIN_EMAIL='p1-customer-attachment-admin@example.test'
export DESKSEED_BOOTSTRAP_ADMIN_DISPLAY_NAME='P1 Customer Attachment Admin'
export DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE="$password_file"

e2e_initialize_resource_identity p1-customer-attachment "$work_dir"
compose_files=(
  --file "$repository_root/compose.yaml"
  --file "$e2e_ownership_overlay_file"
)
e2e_assert_resource_names_absent

compose_up_status=0
if docker compose --project-name "$e2e_project" "${compose_files[@]}" up --build --detach; then
  compose_up_status=0
else
  compose_up_status=$?
fi
capture_status=0
if e2e_capture_owned_resources; then
  capture_status=0
else
  capture_status=$?
fi
if [[ "$compose_up_status" -ne 0 ]]; then
  docker compose --project-name "$e2e_project" "${compose_files[@]}" logs --no-color || true
  exit "$compose_up_status"
fi
if [[ "$capture_status" -ne 0 ]] || ! e2e_assert_expected_stack_captured; then
  printf 'Authenticated customer attachment E2E could not prove exact Compose ownership.\n' >&2
  exit 1
fi

frontend_base_url="http://localhost:$frontend_port"
mailpit_base_url="http://127.0.0.1:$mailpit_port"
ready=false
for _attempt in $(seq 1 45); do
  if curl --fail --silent --show-error "$frontend_base_url/actuator/health" >/dev/null \
    && curl --fail --silent --show-error "$mailpit_base_url/readyz" >/dev/null; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  docker compose --project-name "$e2e_project" "${compose_files[@]}" logs --no-color
  printf 'Authenticated customer attachment E2E stack did not become ready.\n' >&2
  exit 1
fi

(
  cd "$repository_root/frontend"
  PLAYWRIGHT_USE_EXISTING_SERVER=1 \
  PLAYWRIGHT_BASE_URL="$frontend_base_url" \
  DESKSEED_REAL_STACK_MAILPIT_URL="$mailpit_base_url" \
  DESKSEED_REAL_STACK_ADMIN_PASSWORD_FILE="$password_file" \
    npm run test:e2e -- customer-portal.spec.ts \
      --grep 'authenticated customer attachment real stack'
)

printf 'Authenticated customer attachment real-stack Playwright passed.\n'
