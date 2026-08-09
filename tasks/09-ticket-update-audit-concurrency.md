# Codex Brief 09 — Combined Update, Audit, Concurrency

## Requirements

REQ-TKT-007, REQ-TKT-013~015, REQ-AUD-001/002/007.

## In scope

Public/internal composer, status/priority/group/assignee update, one command/one audit, field-aware conflict UI.

## Acceptance

- atomic rollback.
- structured diff.
- same-field 409.
- non-overlap merge.
- red conflict banner preserves draft.
