# Task 29 — Current customer consent projection

## Goal

Task 6 슬라이스로 익명 고객이 registration 또는 request-submission에 필요한 current published consent policy만 안전하고 결정적인 순서로 조회한다.

## Decision and source references

- Decision IDs: D-005, D-008, D-013, D-018, D-058
- Accepted ADRs: 0005, 0008, 0013, 0018
- PRD/domain sections: docs/56 sections 6.1, 6.2, 7.2, 11 Task 6
- API operation: `listCurrentCustomerConsentPolicies`
- Requirement/gates: REQ-CONSENT-001, REQ-CONSENT-002; API-001, API-002, CONSENT-001

## Actor and source

- Actor: anonymous or signed-in CUSTOMER
- Source: CUSTOMER_PORTAL
- Permission: public GET only; no staff session, long-lived secret, or client-supplied actor
- Resource constraint: exactly one allowlisted context and at most 20 current policies
- Request/correlation: request ID is returned only through the bounded problem contract; the public read is not a sensitive access-audit event

## Product and UX contract

- Route: `GET /api/v1/customer/consent-policies?context=REGISTRATION|REQUEST_SUBMISSION`
- Success/empty: exact `{context, policies}` shape; an empty context returns an empty policies array
- Error: invalid context is 400; persistence or canonical-integrity uncertainty is 503
- All responses are `Cache-Control: no-store`

## In scope

- root current-policy projection port and PostgreSQL read adapter
- exact current pointer join filtered by PUBLISHED lifecycle and requested context
- immutable title/document/checksum/required/order/effective-time projection
- anonymous controller, no-store 200/400/503 response translation, and explicit security matcher
- strict response-field, lifecycle isolation, ordering, integrity, and cap tests
- public operation `FROZEN` promotion and generated Core contract bundle

## Out of scope

- registration/request-submission acceptance validation and persistence (Tasks 8 and 14)
- customer acceptance history or staff/audit metadata
- draft/archive existence, counts, and bodies
- scheduled activation, UI editor/preview, and new migration

## Invariants and failure semantics

- Only the root row's exact current immutable version is eligible; draft fields never influence the response.
- Archived, draft, and other-context policies do not affect response existence or count.
- Stable order is immutable `displayOrder`, then `policyKey`, then opaque policy ID.
- Persisted canonical document, plain text, checksum, and immediate effective/published time are revalidated before projection.
- More than 20 eligible rows is an invalid configuration and returns 503 rather than a truncated list.
- The read-only transaction performs no audit, mutation, external I/O, retry, or idempotency write.

## Data and privacy

- Public: policy ID/key/version/title/safe canonical document/checksum/required/order/effective time.
- Excluded: editable draft, plain text, lifecycle, aggregate version, publisher identity, acceptance/customer/audit rows.
- No policy body or query value is written to application logs, webhook payloads, or audit metadata.

## Acceptance scenarios

- Given current published, draft, archived, and other-context rows, when an anonymous customer queries a context, then only exact current immutable versions appear in display order.
- Given the current draft differs from the published version, then published title/document/checksum/version remain stable.
- Given a missing or unknown context, then a bounded no-store 400 problem is returned.
- Given stored document/checksum/plain-text inconsistency or more than 20 eligible rows, then a bounded no-store 503 is returned without policy data.

## Validation

- `cd backend && ./gradlew test --tests '*CustomerConsentProjectionIntegrationTest'`
- `cd backend && ./gradlew test --tests '*CustomerConsent*' --tests '*ApiDocumentationIntegrationTest'`
- `cd backend && ./gradlew contractTest`
- `make docs-check`
- `git diff --check`

## Compatibility and migration

- Additive GET runtime implementation of the already committed contract; only this operation becomes `FROZEN`.
- No migration or backfill; reads additive V80 tables and leaves reserved V81/V82 untouched.
- Rollback removes the route and its `FROZEN` marker together; stored policies remain compatible with administrator lifecycle APIs.

## Human explanation

- Reading through the root current pointer avoids treating mutable draft state or historical versions as customer requirements.
- Revalidating persisted canonical material makes configuration corruption a visible unavailable state instead of serving unverifiable legal copy.
- One indexed PostgreSQL join is the simplest sufficient technology; no cache, search engine, or external service is introduced.
