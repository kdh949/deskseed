#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=e2e-compose-ownership.sh
source "$repository_root/scripts/e2e-compose-ownership.sh"

temporary_root="${TMPDIR:-/tmp}"
temporary_root="${temporary_root%/}"
work_dir="$(mktemp -d "$temporary_root/deskseed-p1-contract-e2e.XXXXXX")"
chmod 700 "$work_dir"
password_file="$work_dir/first-admin-password"
cookie_file="$work_dir/admin.cookies"
attachment_file="$work_dir/clean.pdf"
download_problem_file="$work_dir/download-problem.json"
bootstrap_password="$(LC_ALL=C od -An -N20 -tx1 /dev/urandom | tr -d '[:space:]')"

backend_port="${DESKSEED_P1_E2E_BACKEND_PORT:-18180}"
mailpit_port="${DESKSEED_P1_E2E_MAILPIT_PORT:-18125}"
frontend_port="${DESKSEED_P1_E2E_FRONTEND_PORT:-15174}"

cleanup() {
  local exit_code=$?
  local cleanup_failed=0
  trap - EXIT

  e2e_cleanup_owned_resources || cleanup_failed=1
  for temporary_file in "$password_file" "$cookie_file" "$attachment_file" "$download_problem_file"; do
    if [[ -f "$temporary_file" ]] && ! unlink "$temporary_file"; then
      cleanup_failed=1
    fi
  done
  if [[ -n "$e2e_ownership_overlay_file" && -f "$e2e_ownership_overlay_file" ]] \
    && ! unlink "$e2e_ownership_overlay_file"; then
    cleanup_failed=1
  fi
  if [[ "$work_dir" == "$temporary_root"/deskseed-p1-contract-e2e.?????? ]] \
    && [[ -d "$work_dir" ]] && ! rmdir "$work_dir"; then
    cleanup_failed=1
  fi
  [[ "$cleanup_failed" -eq 0 ]] || exit_code=1
  exit "$exit_code"
}
trap cleanup EXIT

for port in "$backend_port" "$mailpit_port" "$frontend_port"; do
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && ((port <= 65535)) || {
    printf 'Invalid P1 E2E port: %s\n' "$port" >&2
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
            raise SystemExit(f"P1 E2E port is already in use: {raw_port}")
PY

# This is an ephemeral test-only secret. It is never printed, committed, or reused.
[[ "$bootstrap_password" =~ ^[0-9a-f]{40}$ ]] || {
  printf 'Could not create an ephemeral P1 E2E bootstrap password.\n' >&2
  exit 1
}
printf '%s' "$bootstrap_password" >"$password_file"
chmod 600 "$password_file"
printf '%s\n' '%PDF-1.4' 'P1 private upload' '%%EOF' >"$attachment_file"

export DESKSEED_BACKEND_PORT="$backend_port"
export DESKSEED_MAILPIT_PORT="$mailpit_port"
export DESKSEED_FRONTEND_PORT="$frontend_port"
export DESKSEED_RUNTIME_USER="$(id -u):$(id -g)"
export DESKSEED_CORS_ALLOWED_ORIGINS="http://127.0.0.1:$frontend_port"
export DESKSEED_MAIL_PUBLIC_BASE_URL="http://127.0.0.1:$frontend_port"
export DESKSEED_BOOTSTRAP_ADMIN_ENABLED=true
export DESKSEED_BOOTSTRAP_ADMIN_EMAIL='p1-contract-admin@example.test'
export DESKSEED_BOOTSTRAP_ADMIN_DISPLAY_NAME='P1 Contract Admin'
export DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE="$password_file"

e2e_initialize_resource_identity p1-contract "$work_dir"
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
  printf 'P1 real-stack E2E could not prove exact Compose ownership.\n' >&2
  exit 1
fi

frontend_base_url="http://127.0.0.1:$frontend_port"
backend_base_url="http://127.0.0.1:$backend_port"
ready=false
for _attempt in $(seq 1 45); do
  if curl --fail --silent --show-error "$frontend_base_url/actuator/health" >/dev/null \
    && curl --fail --silent --show-error "$backend_base_url/api-docs/specs/core-api-outline-v1.yaml" \
      | grep '^openapi: 3.1.0$' >/dev/null; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  docker compose --project-name "$e2e_project" "${compose_files[@]}" logs --no-color
  printf 'P1 real-stack E2E backend did not become ready.\n' >&2
  exit 1
fi

runtime_spec="$(curl --fail --silent --show-error "$backend_base_url/api-docs/specs/core-api-outline-v1.yaml")"
for operation_id in \
  createAgentSavedView updateAgentSavedView deleteAgentSavedView previewAgentSavedView \
  reorderAgentSavedViews listTicketsInView searchAgentWorkspace executeAgentTicketBatch \
  createAgentAttachmentUpload createCustomerAttachmentUpload \
  downloadAgentAttachment downloadCustomerAttachment \
  createAuditExport getAuditExport downloadAuditExport; do
  grep -F "operationId: $operation_id" <<<"$runtime_spec" >/dev/null || {
    printf 'Runtime Core OpenAPI is missing P1 operationId %s.\n' "$operation_id" >&2
    exit 1
  }
done

csrf_before_login="$(curl --fail --silent --show-error --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  "$frontend_base_url/api/v1/agent/csrf")"
csrf_token="$(printf '%s' "$csrf_before_login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
login_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  --header "X-CSRF-TOKEN: $csrf_token" --header 'Content-Type: application/json' \
  --request POST --data "{\"email\":\"p1-contract-admin@example.test\",\"password\":\"$bootstrap_password\"}" \
  "$frontend_base_url/api/v1/agent/session")"
[[ "$login_status" == 204 ]] || {
  printf 'P1 real-stack E2E staff login returned HTTP %s.\n' "$login_status" >&2
  exit 1
}

current_staff="$(curl --fail --silent --show-error --cookie "$cookie_file" \
  "$frontend_base_url/api/v1/agent/me")"
staff_id="$(printf '%s' "$current_staff" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
csrf_after_login="$(curl --fail --silent --show-error --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  --header "X-Deskseed-Expected-Staff-Id: $staff_id" "$frontend_base_url/api/v1/agent/csrf")"
csrf_token="$(printf '%s' "$csrf_after_login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
staff_headers=(
  --cookie "$cookie_file"
  --cookie-jar "$cookie_file"
  --header "X-Deskseed-Expected-Staff-Id: $staff_id"
  --header "X-CSRF-TOKEN: $csrf_token"
)
staff_json_headers=("${staff_headers[@]}" --header 'Content-Type: application/json')

visible_views="$(curl --fail --silent --show-error "${staff_headers[@]}" "$frontend_base_url/api/v1/agent/views")"
printf '%s' "$visible_views" | python3 -c '
import json,sys
views=json.load(sys.stdin)
assert sum(view.get("scope") == "SYSTEM" for view in views) == 5
assert all(view.get("ticketCountState") in {"EXACT", "OMITTED_VISIBLE_LIMIT"} for view in views)
'

view_definition='{"name":"P1 real-stack personal view","conditions":{"version":1,"all":[{"field":"STATUS","operator":"LESS_THAN_SOLVED","values":[]}],"any":[]},"columns":["TICKET_NUMBER","SUBJECT","STATUS"],"sort":"updatedAt:desc,ticketNumber:desc"}'
created_view="$(curl --fail --silent --show-error "${staff_json_headers[@]}" --request POST \
  --data "{\"scope\":\"PERSONAL\",${view_definition#\{}" "$frontend_base_url/api/v1/agent/views")"
view_key="$(printf '%s' "$created_view" | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')"
view_version="$(printf '%s' "$created_view" | python3 -c 'import json,sys; print(json.load(sys.stdin)["definitionVersion"])')"

preview_response="$(curl --fail --silent --show-error "${staff_json_headers[@]}" \
  --header 'X-Interaction-Id: 11111111-1111-4111-8111-111111111111' --request POST \
  --data "$view_definition" "$frontend_base_url/api/v1/agent/views/preview")"
printf '%s' "$preview_response" | python3 -c '
import json,sys
value=json.load(sys.stdin)
assert isinstance(value["ticketCount"], int) and value["ticketCount"] >= 0
assert value["sort"] == "updatedAt:desc,ticketNumber:desc"
'

create_ticket_body='{"requester":{"name":"P1 Contract Customer","email":"p1-contract-customer@example.test"},"subject":"P1 contract ticket","firstComment":{"visibility":"PUBLIC","body":"P1 contract fixture comment","attachmentIds":[]},"priority":"NORMAL","clientCommandId":"11111111-1111-4111-8111-111111111112"}'
created_ticket="$(curl --fail --silent --show-error "${staff_json_headers[@]}" --request POST --data "$create_ticket_body" \
  "$frontend_base_url/api/v1/agent/tickets")"
ticket_number="$(printf '%s' "$created_ticket" | python3 -c 'import json,sys; print(json.load(sys.stdin)["ticketNumber"])')"
ticket_version="$(printf '%s' "$created_ticket" | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')"

search_response="$(curl --fail --silent --show-error "${staff_json_headers[@]}" \
  --header 'X-Interaction-Id: 11111111-1111-4111-8111-111111111113' --request POST \
  --data "{\"query\":\"$ticket_number\",\"filters\":{},\"sort\":\"score:desc,ticketNumber:desc\",\"cursor\":null,\"limit\":20}" \
  "$frontend_base_url/api/v1/agent/search")"
printf '%s' "$search_response" | python3 -c '
import json
import sys

value = json.load(sys.stdin)
assert value["resultCount"] >= 1
assert value["items"][0]["ticketNumber"] == int(sys.argv[1])
assert value["sort"] == "score:desc,ticketNumber:desc"
' "$ticket_number"

batch_body="{\"items\":[{\"ticketNumber\":$ticket_number,\"expectedVersion\":$ticket_version,\"clientCommandId\":\"p1-batch-priority\",\"command\":{\"type\":\"UPDATE\",\"changedFields\":[\"priority\"],\"priority\":\"HIGH\"}},{\"ticketNumber\":999999,\"expectedVersion\":0,\"clientCommandId\":\"p1-batch-not-found\",\"command\":{\"type\":\"UPDATE\",\"changedFields\":[\"priority\"],\"priority\":\"HIGH\"}}]}"
batch_response="$(curl --fail --silent --show-error "${staff_json_headers[@]}" --request POST --data "$batch_body" \
  "$frontend_base_url/api/v1/agent/tickets/batch-commands")"
printf '%s' "$batch_response" | python3 -c '
import json,sys
results=json.load(sys.stdin)["results"]
assert [item["outcome"] for item in results] == ["SUCCEEDED", "NOT_FOUND"]
assert results[0]["replayed"] is False
'
batch_replay="$(curl --fail --silent --show-error "${staff_json_headers[@]}" --request POST --data "$batch_body" \
  "$frontend_base_url/api/v1/agent/tickets/batch-commands")"
printf '%s' "$batch_replay" | python3 -c '
import json,sys
results=json.load(sys.stdin)["results"]
assert results[0]["outcome"] == "SUCCEEDED" and results[0]["replayed"] is True
assert results[1]["outcome"] == "NOT_FOUND"
'

upload_response="$(curl --fail --silent --show-error "${staff_headers[@]}" --request POST \
  --form "file=@$attachment_file;type=application/pdf" "$frontend_base_url/api/v1/agent/attachments/uploads")"
attachment_id="$(printf '%s' "$upload_response" | python3 -c 'import json,sys; value=json.load(sys.stdin); assert value["scanStatus"] == "CLEAN"; print(value["id"])')"
download_status="$(curl --silent --show-error --output "$download_problem_file" --write-out '%{http_code}' \
  "${staff_headers[@]}" --header 'X-Interaction-Id: 11111111-1111-4111-8111-111111111114' \
  "$frontend_base_url/api/v1/agent/attachments/$attachment_id/download")"
[[ "$download_status" == 404 ]] || {
  printf 'Unlinked private attachment download returned HTTP %s, expected 404.\n' "$download_status" >&2
  exit 1
}

delete_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' "${staff_headers[@]}" \
  --header "If-Match: \"$view_version\"" --request DELETE "$frontend_base_url/api/v1/agent/views/$view_key")"
[[ "$delete_status" == 204 ]] || {
  printf 'P1 personal saved-view deletion returned HTTP %s.\n' "$delete_status" >&2
  exit 1
}

printf 'P1 real-stack contract E2E passed through the frontend proxy.\n'
