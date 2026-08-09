# Codex Implementation Task Template

## Goal

한 문장으로 사용자 또는 외부 시스템이 얻는 결과를 쓴다.

## Decision and source references

- Decision IDs from `docs/25-implementation-decision-register.md`:
- Accepted ADRs:
- PRD/domain sections:
- API contract operation IDs:
- Verification gate IDs:

## Actor and source

- Actor type: CUSTOMER / STAFF / INTEGRATION_CLIENT / TRIGGER / AUTOMATION / SYSTEM
- Source: CUSTOMER_PORTAL / AGENT_WORKSPACE / ADMIN_UI / AUDIT_EXPLORER / PLATFORM_API / TRIGGER / AUTOMATION
- Required role/scopes:
- Resource constraints:
- Interaction/request/correlation semantics:

## Product and UX contract

- Requirement IDs:
- Screen IDs / route IDs:
- OpenAPI operationIds:
- Zendesk parity pattern from docs/51:
- loading/empty/error/denied/conflict states:
- keyboard/focus/accessibility requirements:
- visual regression fixtures and widths:

## In scope

- vertical use case
- migration
- application/domain changes
- endpoint/UI
- tests
- audit records
- docs/OpenAPI

## Out of scope

이번 PR에서 하지 않을 것과 그 이유.

## Invariants and failure semantics

- domain invariants:
- transaction boundary:
- audit obligation:
- audit failure behavior:
- concurrency:
- idempotency/retry:
- external I/O boundary:

## Data and privacy

- data read/written:
- PII/secrets:
- retention category:
- redaction/encryption:
- export/webhook exposure:

## Threats changed

- authorization bypass
- impersonation
- replay/duplicate
- SSRF/XSS
- secret leakage
- audit bypass/tampering
- concurrency/data loss

## Acceptance scenarios

Write Given/When/Then, including negative and failure-injection cases.

## Validation

List exact gate IDs and commands. Use PostgreSQL-backed tests where behavior depends on PostgreSQL.

## Compatibility and migration

- OpenAPI change classification:
- migration/rollback:
- backfill:
- existing client/UI impact:

## Human explanation

- why this domain/transaction boundary:
- why this permission and audit behavior:
- why this is the simplest sufficient technology:
- what measured evidence would change the design:

## Completion report

- what changed and why
- scenario solved
- decisions relied on/changed
- audit/access/security events produced
- scopes and authorization boundary
- failure/retry behavior
- tests run and not run
- migration/rollback notes
- performance evidence
- human owner explanation checklist
