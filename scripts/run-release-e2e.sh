#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[1/2] Customer, staff workspace, conflict, transfer and child full-stack E2E"
bash "$repository_root/scripts/run-customer-request-e2e.sh"

echo "[2/2] Audit Explorer, self-audit, protected reveal and projection full-stack E2E"
bash "$repository_root/scripts/run-audit-explorer-e2e.sh"

echo "PASS: release Core/Audit full-stack E2E completed with isolated disposable stacks."
