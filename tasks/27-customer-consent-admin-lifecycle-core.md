# Task 27 — Customer consent administrator lifecycle core

## Goal

Task 5의 application/JDBC sub-slice로 관리자 consent policy list/create/read/update/publish/archive와 원자적 metadata-only audit를 제공한다.

## Decision and source references

- Decision IDs: D-005, D-008, D-013, D-018, D-058
- Accepted ADRs: 0005, 0008, 0013, 0018
- PRD/domain sections: docs/56 sections 6.1, 6.3, 7.2, 11 Task 5
- API contract operations: admin consent 6 operations remain contract-only until Task 5b HTTP parity
- Verification gates: ARCH-001, ARCH-002, CONSENT-001

## Actor and source

- Actor type: active STAFF ADMIN
- Source: ADMIN_UI
- Required authority: `customer-consent:manage`
- Resource constraints: immutable policy key/context and per-context current-published cap 20
- Request/correlation: bounded server-derived IDs are retained in metadata-only Admin/Security audit

## Product and UX contract

- Requirement ID: REQ-CONSENT-001
- OpenAPI operation IDs: list/create/get/update/publish/archive customer consent policy operations
- HTTP/CSRF/expected-actor/problem rendering: Task 5b

## In scope

- root administration port, commands/views, and stable lifecycle exceptions
- transactional JDBC implementation for list/create/read/update/publish/archive
- strong aggregate-version precondition semantics at the application boundary
- immediate publish with one Clock value for `effectiveAt = publishedAt`
- per-context PostgreSQL transaction serialization and current-policy cap 20
- metadata-only create/update/publish/archive audit in the same transaction
- integration tests for lifecycle/history, access, stale writes, cap concurrency, and audit rollback

## Out of scope

- HTTP controllers, CSRF, expected-actor filter translation, and problem responses (Task 5b)
- OpenAPI `FROZEN` promotion (Task 5b)
- public current-policy projection (Task 6)
- customer acceptance writes (Tasks 8 and 14)

## Invariants and failure semantics

- Create starts with draft version 1 and aggregate version 0.
- Draft updates increment draft and aggregate versions without changing published history.
- Publish copies a validated draft into a new immutable monotonically increasing version and moves one current pointer.
- Publish is immediate; archived policies cannot be edited or republished.
- Archive preserves every version and acceptance reference.
- Stale preconditions, conflicts, access denial, validation, and audit failure are non-mutating.
- Context cap serialization ensures concurrent 19→21 publish attempts yield one success and one conflict.

## Data, privacy, and audit

- Detail reads expose policy documents only to the explicit management port; acceptance/customer rows are never joined.
- Audit metadata contains policy ID/key/context/version/checksum, never title, document JSON, or plain text.
- Required audit insert failure rolls back the policy mutation.

## Validation

- `cd backend && ./gradlew test --tests '*CustomerConsentAdministrationIntegrationTest'`
- `cd backend && ./gradlew test --tests 'dev.deskseed.architecture.ArchitectureTest'`
- `git diff --check`

## Compatibility and migration

- OpenAPI change classification: none in this sub-slice.
- Migration: consumes additive V80; no new migration or backfill.
- Rollback: revert application code before Task 5b; persisted V80 rows remain contract-compatible.

## Human explanation

- PostgreSQL row/advisory transaction locks make optimistic edits and the context cap deterministic.
- Immutable version rows preserve exactly what customers accept while the root draft remains editable.
- Application and audit persistence share one transaction so operators never see a successful unaudited policy mutation.
