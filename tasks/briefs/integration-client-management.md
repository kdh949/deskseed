# Goal

관리자가 직원 계정을 공유하지 않는 machine principal을 발급·회전·중지·폐기하고, 이후 Platform API가 scope와 자원 제한의 교집합만 허용할 수 있는 인증 기반을 제공한다.

## Decision and source references

- Decision IDs: D-012, D-016, D-018, D-019, D-021, D-043
- Accepted ADRs: 0012, 0016
- PRD/domain: docs/18 sections 4, 14, 15; docs/19 sections 3.3, 10; docs/23 section 6
- API operationIds: listIntegrationClients, createIntegrationClient, getIntegrationClient, disableIntegrationClient, revokeIntegrationClient, rotateIntegrationClientCredential
- Verification gates: ARCH-001, ARCH-002, ARCH-004, ACC-007, AUD-001, INT-AUTH-001, INT-AUTH-002, INT-AUTH-003, OPS-003, UI-002, UI-004

## Actor and source

- Management actor: active STAFF ADMIN with `integration:clients:manage`
- Management source: ADMIN_UI
- Future machine actor: INTEGRATION_CLIENT with source PLATFORM_API
- Resource constraints: allowed group IDs, ticket kinds, update fields, and optional IP/CIDR allowlist
- Every mutation/authentication attempt carries bounded request and correlation IDs.

## Product and UX contract

- Requirement: REQ-INT-002
- Screen/route: INT-001, `/integrations/clients`
- Loading, empty, error, denied, and one-time secret states are explicit.
- Secret is rendered only in the create/rotation result dialog and is never persisted by browser storage.
- Dialog focus enters the secret result and returns to the initiating control on close.

## In scope

- Flyway schema for IntegrationClient and credentials
- scoped API key create/list/detail/disable/revoke/rotate admin API
- bounded overlap rotation, required credential expiry, optional IP/CIDR seam
- constant-time strong verifier and generic authentication failure
- scope/resource intersection authorization component
- structured admin/security audit for lifecycle, failures, and successful last use
- admin UI and automated unit, PostgreSQL integration, component, direct URL, and browser tests
- core OpenAPI, UI route catalog, and requirement traceability updates

## Out of scope

- `/api/v1/platform/**` endpoints and ticket/customer operations
- OAuth, webhook, SDK, external references, rate limiting, idempotency, and ETag
- browser storage of API credentials
- re-enabling a disabled or revoked client

## Invariants and failure semantics

- Raw secrets exist only in create/rotate call memory and their one response.
- Database stores PBKDF2 verifier only; public key ID selects a credential row.
- A client has at most one ACTIVE and one bounded RETIRING credential.
- Rotation revokes an older RETIRING credential before creating the next pair.
- Disabled/revoked client, revoked/expired/out-of-overlap credential, IP mismatch, malformed key, and wrong secret all fail with one generic authentication error.
- Client mutation and canonical admin/security audit commit or roll back together.
- Successful authentication updates last-used metadata and appends its security event atomically.
- There is no external network I/O.

## Data and privacy

- Critical: API key secret; never persisted, logged, audited, projected, or stored in browser storage.
- Security metadata: public key ID, expiry, lifecycle status, last-used time/IP, scope/constraint summary.
- Admin/security retention default follows the 365-day category; client/credential rows remain until a later reviewed retention policy.

## Threats changed

- Reduces shared-staff-account impersonation and excessive integration privilege.
- Limits stolen-key blast radius with expiry, revocation, rotation overlap, scope, constraints, and network seam.
- Prevents credential enumeration through generic failures and a dummy-verifier path.
- Prevents secret disclosure through response schemas, audit allowlists, log scans, and browser-storage tests.
- Preserves repudiation evidence with atomic lifecycle/authentication audit.

## Acceptance scenarios

- Given an authorized admin, create returns a `dsk_live_<public-id>.<secret>` once and later detail/list omit it.
- Given a valid active key and allowed IP, authentication returns INTEGRATION_CLIENT and records last use.
- Given malformed, wrong, expired, revoked, disabled, out-of-overlap, or IP-denied credentials, authentication returns the same generic failure.
- Given rotation overlap, old and new keys work until the bounded deadline; after it only the new key works.
- Given broad scope plus narrow group/field/kind constraints, a request is allowed only when all configured constraints match.
- Given AGENT or SECURITY_AUDITOR, management API and direct UI route are denied.
- Given an audit insert failure, lifecycle mutation and last-use update roll back.

## Compatibility and migration

- Additive core admin API; no Platform API contract change.
- Forward-only V18 migration; rollback is application rollback plus backup/restore or a reviewed forward fix.
- No backfill and no existing client/UI behavior change.
