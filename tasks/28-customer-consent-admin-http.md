# Task 28 — Customer consent administrator HTTP parity

## Goal

Task 5b sub-slice로 승인된 관리자 consent policy 6개 operation의 HTTP, security, problem, ETag, no-store, request-size 및 runtime OpenAPI parity를 완성한다.

## Decision and source references

- Decision IDs: D-005, D-008, D-013, D-018, D-058
- Accepted ADRs: 0005, 0008, 0013, 0018
- Contract: `api/core-api-fragments/05-customer-consent.yaml`
- Operations: `listCustomerConsentPolicies`, `createCustomerConsentPolicy`, `getCustomerConsentPolicy`, `updateCustomerConsentPolicyDraft`, `publishCustomerConsentPolicy`, `archiveCustomerConsentPolicy`
- Requirement/gates: REQ-CONSENT-001; API-001, API-002, AUTHZ-001, AUTHZ-002, CONSENT-001

## Actor, authorization, and audit

- Active STAFF ADMIN session plus `customer-consent:manage`
- Required canonical expected-actor header on all six operations
- CSRF on POST/PUT mutations
- ADMIN_UI request/correlation context passed to the Task 5a service
- Mutation audit semantics remain in Task 5a; HTTP never logs request document/body

## In scope

- controller request/response translation for all six admin operations
- explicit route authority matcher plus application role/capability defense in depth
- strong quoted ETag/If-Match and create If-None-Match handling
- bounded 262,144-byte request stream for create/update
- stable no-store 400/401/403/404/409/412/503 problems
- source OpenAPI admin operations promoted to FROZEN and deterministic bundle regeneration
- MockMvc lifecycle/auth/CSRF/precondition/audit rollback/request-boundary tests

## Out of scope

- customer current-policy endpoint (Task 6)
- acceptance persistence commands (Tasks 8 and 14)
- UI editor/preview (Task 17)

## Invariants and failure semantics

- Missing or stale mutation preconditions never mutate and return a strong current-version ETag.
- Response ETag equals aggregateVersion; all success/error responses are no-store.
- List response omits draft/document/history bodies.
- Detail response includes bounded immutable history but no acceptance/customer identity.
- V80 `updated_at` is the draft's last mutation timestamp; publish/archive use immutable `publishedAt` and audit time without falsifying `draft.updatedAt`.
- Oversized or malformed documents fail without persistence or body reflection.

## Validation

- `cd backend && ./gradlew test --tests '*AdminCustomerConsent*' --tests '*CustomerConsent*'`
- runtime/committed OpenAPI parity and focused documentation tests
- `make docs-check`
- `git diff --check`

## Compatibility and migration

- No new migration; reserved V81/V82 remain untouched.
- Source Core fragment is authoritative; bundled outline is generated in a separate commit.
- Rollback removes routes/FROZEN markers together while Task 5a data remains compatible.

## Human explanation

- Security filters reject invalid session/actor/CSRF before the service, and the service repeats role/capability checks.
- Strong ETags turn browser stale state into an explicit refresh path instead of silent overwrite.
- Bounded streaming protects the body before JSON persistence while canonical validation protects document semantics.
