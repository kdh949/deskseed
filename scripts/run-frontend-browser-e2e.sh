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

workspace_specs=(
  access-surface.spec.ts
  agent-views-workspace.spec.ts
  frontend-system.spec.ts
  ticket-workspace.spec.ts
)

"$playwright" test "${workspace_specs[@]}"
