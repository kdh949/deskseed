# AI V1 A — 계약, 요청 바인딩, PUBLIC source 경계

## Goal

권한 있는 상담사가 티켓 번호와 feature로 AI job을 멱등 등록하고, AI service가 등록된 job binding으로만 현재 PUBLIC 대화를 읽을 수 있다.

## Decision and source references

- Decision IDs: D-001, D-002, D-003, D-005, D-008, D-009, D-013, D-018, D-021, D-036, D-054, D-055, D-066
- Accepted ADRs: 0002, 0003, 0005, 0008, 0009, 0013, 0018, 0025, 0040, 0049
- PRD/domain: docs/01, docs/02, docs/03, docs/19, docs/23, AI design v1.2 sections 0-10 and D.1-D.3
- API operation IDs: createAgentAiJob, listAgentAiJobs, cancelAgentAiJob, getInternalAiRequestContext, getInternalAiRequestContextRevision
- Verification gates: ARCH-001/002/003/004, TKT-002, ACC-002/007, IDEM-001/002/003, AI-API-001, AI-SRC-001

## Actor and source

- Staff command/read actor: `STAFF`, source `AGENT_UI`, authenticated session, AGENT/ADMIN + `AGENT_WORKSPACE`.
- Machine source read actor: fixed `INTEGRATION_CLIENT`, source `AI_SERVICE`, `ai:ticket-context:read`, registered job binding only.
- Resource constraints: one server-owned workspace, owner staff ID, ticket UUID resolved from numeric ticket number, feature and request revision.
- Request/correlation IDs are bounded and propagated; idempotency key is fingerprinted and never stored raw.

## Product and API contract

- Requirements: REQ-AI-001, REQ-AI-002
- UI/route: none; `DEFERRED_UI`
- Staff create/list/cancel uses existing staff session/CSRF/expected-actor conventions.
- Internal source returns ordered PUBLIC comment IDs, timestamps and bounded bodies only. It excludes INTERNAL comments, customer profile, child relation, attachment URLs, staff-only fields and audit content.
- Polling/listing metadata does not emit `TICKET_VIEWED`.

## In scope

- OpenAPI schemas and problems for this slice.
- Backend Flyway migration for request binding, idempotency and dedicated outbox.
- AI-assistance root module and ticketing root PUBLIC projection API.
- staff create/list/cancel controller and machine source controller/authentication boundary.
- required access audit, tests, docs and generated Core bundle.

## Out of scope

- Python job execution, Redis, model call and result body (B onward).
- summary/triage/reply quality behavior (C-F).
- admin operations, feedback, retention/eval (G).
- every frontend/Storybook/browser change.

## Invariants and failure semantics

- `Ticket.description` is not introduced; only `ticket_comments.visibility='PUBLIC'` is selected.
- one `(workspace, requester, idempotency-key fingerprint)` maps to one canonical request fingerprint; different payload is 409.
- request row and outbound intent commit together. No HTTP happens in that transaction.
- current active staff and ticket read capability are checked before create, source read and list/cancel.
- required audit failure rolls back/withholds sensitive content.
- cancellation increments request revision and creates an ordered outbox intent; create arriving after cancellation cannot revive the job.
- same cancel is idempotent. terminal AI state reconciliation is a later slice.

## Data and privacy

- Backend stores IDs, feature/options, hashes/revisions, lifecycle metadata and body-free outbox payload.
- raw idempotency key, comment body, customer profile and provider/service secret are absent from request/outbox/logs.
- PUBLIC comment body exists only in the source response and process memory for the authorized request.
- request metadata default retention is 30 days; result retention is owned by AI DB in later slices.

## Threats changed

- cross-ticket/job IDOR, staff impersonation, inactive requester, replay/mismatch, INTERNAL leakage, audit bypass, log/control-character injection and outbox duplication.

## Acceptance scenarios

1. Given an active agent with ticket read access, when a valid request with a new key is sent, then one request and one body-free outbox intent commit and 202 returns.
2. Given the same key and canonical request, when retried, then the existing job is returned without another request/outbox/audit side effect.
3. Given the same key with another feature/options/version, when retried, then 409 returns without mutation.
4. Given PUBLIC and INTERNAL sentinel comments, when the machine reads the bound context, then only PUBLIC content is returned and an `INTEGRATION_CLIENT` access audit exists.
5. Given an unbound job, wrong ticket, disabled requester, invalid service credential or audit persistence failure, then no source body is returned.
6. Given a cancel before create relay delivery, when events arrive out of order, then monotonic request revision/tombstone prevents revival.

## Validation

- `cd backend && ./gradlew integrationTest --tests '*Ai*'`
- `cd backend && ./gradlew fastTest contractTest migrationTest`
- `python3 scripts/bundle_core_openapi.py --check`
- `make docs-check`
- `git diff --check`

## Compatibility and migration

- Additive Core operations; existing frontend clients are unchanged.
- Forward-only Flyway migration with feature disabled by default.
- No backfill; existing tickets become eligible only through a new authorized job.
- Rollback: disable AI, stop relay, preserve request/outbox for forward recovery, restore DB only for full migration rollback.

## Human explanation

The Backend owns identity, PUBLIC projection and canonical audit because it owns ticket truth. The Python service receives no business DB credential. Two outboxes avoid a distributed transaction and duplicate delivery is handled as normal state. This is the smallest slice that proves the privacy and delivery boundary before adding model execution.
