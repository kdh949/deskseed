# Integration client rate policy vertical slice

## Goal

관리자가 기존 scoped Platform API client별 분당 요청 한도와 사용 현황을 안전하게 조회·변경하고, 다음 machine request부터 PostgreSQL 공유 limiter가 그 한도를 적용한다.

## Decision and source references

- Decision IDs: D-008, D-012, D-016, D-018, D-021, D-022, D-023, D-043
- Accepted ADRs: ADR 0012, ADR 0016, ADR 0031, ADR 0040
- Requirements: REQ-INT-001, REQ-INT-002, REQ-INT-006
- Contract operations: `getIntegrationClientRatePolicy`, `updateIntegrationClientRatePolicy`; committed Platform v1 operations retain `OPAQUE_API_KEY` only.
- Verification gates: docs/21 DOC-001, BE-001, BE-002, BE-003

## Actor and source

- Configuration actor: STAFF with `integration:clients:manage`, source `ADMIN_UI`, session + CSRF + request/correlation context.
- Runtime actor: `INTEGRATION_CLIENT`, source `PLATFORM_API`; opaque API key, scope, private-network and resource-constraint checks remain unchanged.
- A request's usage counter is incremented after successful machine authentication, including a subsequent 429, because it represents authenticated Platform API use rather than successful ticket mutation.

## In scope

- V62 additive client columns for a bounded per-minute policy and authenticated usage counter.
- Admin read/update endpoint with `If-Match`, no-store, an ETag, admin/security audit and audit-failure rollback.
- Per-client effective limit passed into the existing PostgreSQL fixed-window limiter.
- OpenAPI/auth-strategy metadata, task/progress evidence, PostgreSQL-backed tests.

## Out of scope

- No OAuth endpoint, token issuer, inactive-authenticator wiring, custom field/tag/status operation, frontend page, Redis, broker, or rate-limit cache.
- No change to the Foundation-owned Core API base or existing Platform operation paths.

## Invariants and failure semantics

- Policy updates compare the current IntegrationClient optimistic version. A stale `If-Match` is a 412 with the current ETag and no mutation.
- Policy is restricted by a server-admin min/max and DB check range. Authenticated machine requests use the persisted client value, but a shared limiter persistence failure still returns 503 fail-closed.
- The policy mutation and `INTEGRATION_CLIENT_RATE_LIMIT_UPDATED` admin/security audit commit or roll back together.
- API key raw secret remains verifier-only; it is not returned by this surface and never appears in policy metadata.

## Threats changed

- Direct URL and service calls require the same explicit capability.
- CSRF, session actor context, version precondition and audit failure prevent unauthorized or partial policy changes.
- The active strategy remains opaque API key; no fake OAuth success path is exposed.
- The rate-limit decision remains PostgreSQL authoritative across instances, with no in-memory bypass.

## Acceptance scenarios

- Given an ADMIN client policy, GET returns no-store, ETag, bounded limit, usage count and last-used time without key material.
- Given the GET ETag and valid CSRF, PATCH changes the effective limit, appends the security audit and causes Platform response headers/429 to use the new value.
- Given a stale ETag, missing CSRF, missing authority, invalid limit, or required audit write failure, no rate policy is partially changed.

## Validation

- `make docs-check`
- focused Testcontainers integration tests for admin policy, Platform rate limits, authentication and API contracts
- backend compile and architecture test

## Compatibility and migration

- OpenAPI adds a new admin resource only; existing Platform v1 operations are unchanged.
- V62 adds nullable-in-use-safe defaults and checks. Rollback leaves inert columns in place; no applied migration is edited or removed.
