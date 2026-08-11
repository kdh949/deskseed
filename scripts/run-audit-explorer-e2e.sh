#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
e2e_project="deskseed-audit-e2e-$$"
backend_port="${DESKSEED_E2E_BACKEND_PORT:-28080}"
frontend_port="${DESKSEED_E2E_FRONTEND_PORT:-25173}"
admin_email="e2e-admin@deskseed.test"
admin_password="Deskseed E2E admin 42!"
admin_password_file="$(mktemp /tmp/deskseed-audit-e2e-admin-password.XXXXXX)"
chmod 600 "$admin_password_file"
printf '%s' "$admin_password" >"$admin_password_file"

cleanup() {
  docker compose --project-name "$e2e_project" --file "$repository_root/compose.yaml" \
    down --volumes --remove-orphans
  rm -f "$admin_password_file"
}

trap cleanup EXIT

DESKSEED_BACKEND_PORT="$backend_port" \
DESKSEED_FRONTEND_PORT="$frontend_port" \
DESKSEED_CORS_ALLOWED_ORIGINS="http://127.0.0.1:$frontend_port" \
DESKSEED_BOOTSTRAP_ADMIN_ENABLED="true" \
DESKSEED_BOOTSTRAP_ADMIN_EMAIL="$admin_email" \
DESKSEED_BOOTSTRAP_ADMIN_DISPLAY_NAME="E2E 관리자" \
DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE="$admin_password_file" \
  docker compose --project-name "$e2e_project" --file "$repository_root/compose.yaml" \
  up --build --detach

stack_ready=false
for _attempt in $(seq 1 60); do
  if curl --fail --silent "http://127.0.0.1:$backend_port/actuator/health" >/dev/null \
    && curl --fail --silent "http://127.0.0.1:$frontend_port/" >/dev/null; then
    stack_ready=true
    break
  fi
  sleep 2
done

if [[ "$stack_ready" != "true" ]]; then
  docker compose --project-name "$e2e_project" --file "$repository_root/compose.yaml" logs --no-color
  echo "Audit Explorer E2E stack did not become ready within 120 seconds." >&2
  exit 1
fi

cd "$repository_root/frontend"
if ! PLAYWRIGHT_USE_EXISTING_SERVER=1 \
  PLAYWRIGHT_BASE_URL="http://127.0.0.1:$frontend_port" \
  E2E_FULL_STACK=1 \
  DESKSEED_E2E_COMPOSE_PROJECT="$e2e_project" \
  DESKSEED_E2E_ADMIN_EMAIL="$admin_email" \
  DESKSEED_E2E_ADMIN_PASSWORD="$admin_password" \
    npx playwright test audit-explorer.full-stack.spec.ts; then
  docker compose --project-name "$e2e_project" --file "$repository_root/compose.yaml" \
    logs --no-color --tail 200 backend frontend
  exit 1
fi
