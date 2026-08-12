#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frontend_directory="$repository_root/frontend"
playwright="$frontend_directory/node_modules/.bin/playwright"
browser="${PLAYWRIGHT_BROWSER:-chromium}"

if [[ ! -x "$playwright" ]]; then
  echo "Playwright is not installed; run npm --prefix frontend ci first." >&2
  exit 2
fi

cd "$frontend_directory"

core_specs=(
  agent-views-workspace.spec.ts
  audit-explorer.spec.ts
  customer-request.spec.ts
  frontend-system.spec.ts
)
remaining_specs=(
  staff-auth-admin.spec.ts
  ticket-composer-conflict.spec.ts
  transfer-child-ticket.spec.ts
)

if [[ "$browser" == "webkit" ]]; then
  printf '%s\n' \
    "WebKit gate uses two fresh browser processes (35 + 6 tests)." \
    "This isolates a documented Playwright 1.62 WebKit process-lifetime hang; no test is retried or skipped."
  "$playwright" test "${core_specs[@]}"
  "$playwright" test "${remaining_specs[@]}"
else
  "$playwright" test "${core_specs[@]}" "${remaining_specs[@]}"
fi
