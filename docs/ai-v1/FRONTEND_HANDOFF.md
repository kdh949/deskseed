# AI V1 frontend handoff

Status: `DEFERRED_UI`

No file under `frontend/` is changed by the server/runtime slice. This document freezes the contract and failure states a later Agent Ticket Workspace task must implement. Live provider, deployed-environment, and human quality validation remain separate.

## Agent flow

1. `POST /api/v1/agent/tickets/{ticketNumber}/ai/jobs` with `Idempotency-Key`, CSRF, expected staff actor, feature, and the ticket version currently rendered.
2. Poll `GET .../ai/jobs/{jobId}` without `includeResult`; this returns metadata only and does not emit `TICKET_VIEWED` or result-read audit.
3. On `SUCCEEDED`, call the same endpoint once with `includeResult=true`. Backend rechecks owner/current ticket permission, feature flag, PUBLIC context revision, expiry, citations, and required access-audit persistence.
4. Keep all generated content visually distinct and require explicit human insertion. `canInsert=false`, `stale=true`, `NEEDS_REVIEW`, or any non-success terminal state must disable insertion.
5. Immediately before insertion, use the existing ticket command with current optimistic concurrency semantics. The AI result is never an authorization token.
6. Send optional feedback with a new `Idempotency-Key`; never send the edited reply body.

## States to implement later

- Loading/polling with `pollAfterMs` and bounded backoff.
- Empty/no prior jobs.
- Feature disabled or actor not in allowlist.
- 404 existence-safe denial, 409 stale ticket/idempotency conflict, 429 with `Retry-After`, and 503 dependency/audit failure.
- `CANCELLED`, `SUPERSEDED`, `EXPIRED`, `FAILED`, and body-free `NEEDS_REVIEW`.
- Result expired or source/KB became stale between generation and display.

PUBLIC and INTERNAL composer drafts remain separate. AI must never auto-submit, overwrite a draft, mutate priority/tags, or copy INTERNAL content into a request. No endpoint or schema should be invented in frontend code; the committed Core OpenAPI is the source of truth.

## Result shapes

- Summary: problem, attempted actions, unresolved items, next checks.
- Triage: fixed topic code, optional fixed priority, validated tag IDs, reasons. Until a current allowed-tag source is contracted, server output with tag IDs fails closed.
- Reply draft: answer plus approved PUBLIC KB citations. A reply with no approved citation is not returned as usable content.

## Frontend verification still required

Storybook interaction/accessibility, responsive browser checks, API transport tests, and draft-preservation tests are `NOT RUN` in this server-only slice.
