#!/bin/sh
set -eu

API_URL="${API_URL:-http://localhost:8080}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

cat > "$TEMP_DIR/request.json" <<'JSON'
{
  "name": "데모 고객",
  "email": "demo@example.com",
  "subject": "결제가 되지 않아요",
  "message": "결제 버튼을 누르면 오류가 발생합니다."
}
JSON

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  --data @"$TEMP_DIR/request.json" \
  "$API_URL/api/v1/requests" > "$TEMP_DIR/submitted.json"

TICKET_NUMBER="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["ticketNumber"])' < "$TEMP_DIR/submitted.json")"
ACCESS_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])' < "$TEMP_DIR/submitted.json")"

printf 'Created ticket #%s\n' "$TICKET_NUMBER"
printf 'Verifying the ticket-scoped lookup without printing its bearer token.\n\n'

curl --fail --silent --show-error \
  -H "X-Request-Access-Token: $ACCESS_TOKEN" \
  "$API_URL/api/v1/requests/$TICKET_NUMBER" \
  | python3 -m json.tool
