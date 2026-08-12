#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=e2e-compose-ownership.sh
source "$repository_root/scripts/e2e-compose-ownership.sh"
e2e_temp_root="${TMPDIR:-/tmp}"
e2e_temp_root="${e2e_temp_root%/}"
e2e_work_dir="$(mktemp -d "$e2e_temp_root/deskseed-smoke.XXXXXX")"
chmod 700 "$e2e_work_dir"

cleanup() {
  local exit_code=$?
  local cleanup_failed=0
  trap - EXIT
  e2e_cleanup_owned_resources || cleanup_failed=1
  if [[ "$e2e_work_dir" == "$e2e_temp_root"/deskseed-smoke.?????? ]]; then
    if [[ -n "$e2e_ownership_overlay_file" && -e "$e2e_ownership_overlay_file" ]] \
      && ! unlink "$e2e_ownership_overlay_file"; then
      cleanup_failed=1
    fi
    if [[ -d "$e2e_work_dir" ]] && ! rmdir "$e2e_work_dir"; then
      cleanup_failed=1
    fi
  else
    printf 'Refusing unexpected smoke work directory cleanup: %s\n' "$e2e_work_dir" >&2
    cleanup_failed=1
  fi
  [[ "$cleanup_failed" -eq 0 ]] || exit_code=1
  exit "$exit_code"
}

trap cleanup EXIT

e2e_initialize_resource_identity smoke "$e2e_work_dir"
compose_files=(
  --file "$repository_root/compose.yaml"
  --file "$e2e_ownership_overlay_file"
)
e2e_assert_resource_names_absent

compose_up_status=0
if docker compose --project-name "$e2e_project" "${compose_files[@]}" \
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
  printf 'Compose smoke startup failed.\n' >&2
  exit "$compose_up_status"
fi
if [[ "$capture_status" -ne 0 ]] || ! e2e_assert_expected_stack_captured; then
  printf 'Compose smoke could not prove exact run ownership for the started stack.\n' >&2
  exit 1
fi

backend_port="${DESKSEED_BACKEND_PORT:-8080}"
mailpit_port="${DESKSEED_MAILPIT_PORT:-8025}"
for _attempt in $(seq 1 30); do
  if curl --fail --silent --show-error "http://localhost:$backend_port/actuator/health" >/dev/null \
    && curl --fail --silent --show-error "http://localhost:$mailpit_port/readyz" >/dev/null; then
    exit 0
  fi
  sleep 2
done

docker compose --project-name "$e2e_project" "${compose_files[@]}" logs --no-color
echo "Backend health or Mailpit ready endpoint did not return HTTP 200 within 60 seconds." >&2
exit 1
