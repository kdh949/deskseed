#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=e2e-compose-ownership.sh
source "$repository_root/scripts/e2e-compose-ownership.sh"
e2e_temp_root="${TMPDIR:-/tmp}"
e2e_temp_root="${e2e_temp_root%/}"
e2e_work_dir="$(mktemp -d "$e2e_temp_root/deskseed-customer-e2e.XXXXXX")"
chmod 700 "$e2e_work_dir"
backend_port="${DESKSEED_E2E_BACKEND_PORT:-18080}"
frontend_port="${DESKSEED_E2E_FRONTEND_PORT:-15173}"
mailpit_port="${DESKSEED_E2E_MAILPIT_PORT:-18025}"
admin_email="e2e-admin@deskseed.test"
admin_password="Deskseed E2E admin 42!"
admin_password_file="$e2e_work_dir/admin-password"
e2e_container_uid="$(id -u)"
e2e_container_gid="$(id -g)"
export DESKSEED_E2E_CONTAINER_UID="$e2e_container_uid"
export DESKSEED_E2E_CONTAINER_GID="$e2e_container_gid"

cleanup() {
  local exit_code=$?
  local cleanup_failed=0
  trap - EXIT
  e2e_cleanup_owned_resources || cleanup_failed=1
  if [[ "$e2e_work_dir" == "$e2e_temp_root"/deskseed-customer-e2e.?????? ]]; then
    if [[ -e "$admin_password_file" ]] && ! unlink "$admin_password_file"; then
      cleanup_failed=1
    fi
    if [[ -n "$e2e_ownership_overlay_file" && -e "$e2e_ownership_overlay_file" ]] \
      && ! unlink "$e2e_ownership_overlay_file"; then
      cleanup_failed=1
    fi
    if [[ -d "$e2e_work_dir" ]] && ! rmdir "$e2e_work_dir"; then
      cleanup_failed=1
    fi
  else
    printf 'Refusing unexpected E2E work directory cleanup: %s\n' "$e2e_work_dir" >&2
    cleanup_failed=1
  fi
  [[ "$cleanup_failed" -eq 0 ]] || exit_code=1
  exit "$exit_code"
}

trap cleanup EXIT

e2e_initialize_resource_identity customer "$e2e_work_dir"
compose_files=(
  --file "$repository_root/compose.yaml"
  --file "$repository_root/compose.e2e.yaml"
  --file "$e2e_ownership_overlay_file"
)
: >"$admin_password_file"
chmod 600 "$admin_password_file"
printf '%s' "$admin_password" >"$admin_password_file"
e2e_assert_resource_names_absent

compose_up_status=0
if DESKSEED_BACKEND_PORT="$backend_port" \
  DESKSEED_FRONTEND_PORT="$frontend_port" \
  DESKSEED_MAILPIT_PORT="$mailpit_port" \
  DESKSEED_CORS_ALLOWED_ORIGINS="http://127.0.0.1:$frontend_port" \
  DESKSEED_BOOTSTRAP_ADMIN_ENABLED="true" \
  DESKSEED_BOOTSTRAP_ADMIN_EMAIL="$admin_email" \
  DESKSEED_BOOTSTRAP_ADMIN_DISPLAY_NAME="E2E 관리자" \
  DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE="$admin_password_file" \
  DESKSEED_E2E_CONTAINER_UID="$e2e_container_uid" \
  DESKSEED_E2E_CONTAINER_GID="$e2e_container_gid" \
    docker compose --project-name "$e2e_project" "${compose_files[@]}" \
    up --build --detach; then
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
  printf 'Customer request E2E Compose startup failed.\n' >&2
  exit "$compose_up_status"
fi
if [[ "$capture_status" -ne 0 ]] || ! e2e_assert_expected_stack_captured; then
  printf 'Customer request E2E could not prove exact run ownership for the started stack.\n' >&2
  exit 1
fi

stack_ready=false
for _attempt in $(seq 1 60); do
  if curl --fail --silent "http://127.0.0.1:$backend_port/actuator/health" >/dev/null \
    && curl --fail --silent "http://127.0.0.1:$frontend_port/" >/dev/null \
    && curl --fail --silent "http://127.0.0.1:$mailpit_port/readyz" >/dev/null; then
    stack_ready=true
    break
  fi
  sleep 2
done

if [[ "$stack_ready" != "true" ]]; then
  docker compose --project-name "$e2e_project" "${compose_files[@]}" logs --no-color
  echo "Customer request E2E stack did not become ready within 120 seconds." >&2
  exit 1
fi

cd "$repository_root/frontend"
if ! PLAYWRIGHT_USE_EXISTING_SERVER=1 \
  PLAYWRIGHT_BASE_URL="http://127.0.0.1:$frontend_port" \
  E2E_FULL_STACK=1 \
  DESKSEED_E2E_COMPOSE_PROJECT="$e2e_project" \
  DESKSEED_E2E_ADMIN_EMAIL="$admin_email" \
  DESKSEED_E2E_ADMIN_PASSWORD="$admin_password" \
  DESKSEED_E2E_MAILPIT_URL="http://127.0.0.1:$mailpit_port" \
    npx playwright test customer-request.full-stack.spec.ts; then
  docker compose --project-name "$e2e_project" "${compose_files[@]}" \
    logs --no-color --tail 200 backend frontend
  exit 1
fi
