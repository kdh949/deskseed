# Private Platform API v1 implementation brief

## Goal

사설망의 IntegrationClient가 상담사 계정을 사칭하거나 공유하지 않고 ticket을 생성·조회·제한 수정하고 INTERNAL comment를 남긴다.

## Decision and source references

- Decision IDs: D-012, D-016, D-018, D-021, D-022, D-023, D-043.
- Accepted ADRs: ADR 0012, ADR 0016, ADR 0031.
- PRD/domain: `docs/18-integration-platform.md`, `docs/19-security-audit-center.md`, `docs/20-api-sdk-contract.md`.
- API operationIds: `platformCreateTicket`, `platformGetTicket`, `platformUpdateTicket`, `platformAddInternalComment`.
- Verification gates: PLAT-001, PLAT-002, INT-AUTH-001~004, IDEM-001~004, CONC-001, ACC-006, ACC-007, ARCH-001/002/004, CHG-001/002/003.

## Actor and source

- Actor type: `INTEGRATION_CLIENT` only.
- Source: `PLATFORM_API` only.
- Required scopes: `tickets:create`, `tickets:read`, `tickets:update`, `tickets:comment:internal` per operation.
- Resource constraints: allowed group, ticket kind, changed field, credential IP allowlist의 교집합. 설정된 dimension이 request/resource에 없으면 deny한다.
- Request semantics: server-generated/validated request and correlation IDs; command identity is IntegrationClient + operationId + Idempotency-Key.
- Client-supplied staff/customer actor headers and body fields never select the canonical actor.

## Product and API contract

- Requirement IDs: REQ-INT-001, REQ-INT-002, REQ-INT-003, REQ-INT-004.
- Route IDs: `/api/v1/platform/tickets`, `/api/v1/platform/tickets/{ticketNumber}`, `/api/v1/platform/tickets/{ticketNumber}/internal-comments`.
- Contract: `api/platform-api-outline-v1.yaml`.
- No browser UI or browser key persistence is part of this machine API.

## In scope

- PostgreSQL idempotency receipt and nullable requester/`INTERNAL_WORK_ITEM` ticket schema evolution.
- Dedicated stateless Platform security chain, API-key principal, private/trusted-proxy network boundary, per-client rate limiting.
- Customer request creation with a first PUBLIC customer comment.
- Internal work item creation with a first INTERNAL IntegrationClient comment; requester is optional and no customer is fabricated when omitted.
- Integration-safe read, allowlisted exact-version update, INTERNAL comment.
- Ticket change audit, Platform read access audit, authentication/rate-limit/security denial audit.
- RFC 9457 errors, OpenAPI examples/contract checks, unit/PostgreSQL/HTTP integration tests.

## Out of scope

- OAuth, public-Internet deployment, PUBLIC follow-up comments, webhook, SDK, export, attachment, admin/settings API.
- Arbitrary fields, transfer, child relation, ExternalReference mutation, customer profile projection.
- Distributed rate-limit infrastructure; the v1 single-node private deployment uses a bounded in-memory window seam.

## Invariants and failure semantics

- `CUSTOMER_REQUEST.message` is the first PUBLIC comment; `INTERNAL_WORK_ITEM.message` is the first INTERNAL comment.
- Every mutation and its ordered TicketAudit commit or roll back together.
- Authentication last-use/security audit failure fails closed.
- Read access audit failure fails the read; no success is returned without `API_RESOURCE_READ`.
- Update requires an exact `If-Match: \"ticket-v{version}\"`; stale versions return structured 412 without mutation.
- Idempotency identity is `(client_id, operation_id, idempotency_key)` and the canonical hash covers operation, canonical path, body, and `If-Match` where applicable.
- Same identity/hash replays the stored status, selected headers, and body. A different hash returns 409. A concurrent same-hash request either replays after the winner commits or returns documented in-progress 409 with `Retry-After`.
- Reservation, business mutation, audit, and final response receipt share one database transaction. A pre-commit crash rolls back all of them; a post-commit response loss replays the committed receipt.
- There is no external I/O inside the ticket transaction.

## Data and privacy

- Writes: ticket, initial/internal comment, ticket audit/events, idempotency receipt; customer row only for `CUSTOMER_REQUEST`.
- Secrets: Authorization/API-key material is never persisted in idempotency, ticket audit, access audit, security audit, application log, response body, or metric.
- Request bodies and comment text are not stored in idempotency receipts beyond the already-authorized response/body necessary for exact replay; canonical request hashing is one-way SHA-256.
- Retention: idempotency receipts expire after the configurable 7-day launch default; canonical ticket/audit retention is unchanged.
- No webhook/export exposure.

## Threats changed

| Threat | Boundary/mitigation | Required evidence |
|---|---|---|
| Public or spoofed source reaches Platform API | Direct peer allowlist; forwarded chain trusted only from configured proxy CIDRs | direct public and spoofed X-Forwarded-For tests |
| Credential theft/replay | slow verifier, expiry/revoke/rotation/IP constraint, per-client rate limit | lifecycle and IP tests |
| Scope/resource escalation | server-derived principal and stored ticket dimensions; scope ∩ constraints ∩ fields | missing scope and every constraint denial |
| Staff impersonation | ignore/reject actor-like input; audit actor comes only from authenticated principal | header/body spoof regression |
| Duplicate mutation | transactional receipt and canonical request hash | sequential/concurrent/crash replay tests |
| Lost update | exact ETag/If-Match | matching/stale tests |
| INTERNAL/customer leak | Platform projection omits comments/customer profile; customer surface filters INTERNAL server-side | customer non-leak test |
| Secret/log injection | bounded IDs/metadata; no Authorization/body logging | captured-log and audit scans |
| Audit bypass | mutation/read/security audit are required and transactional | failure injection tests |

## Acceptance scenarios

- Given a valid private source and create scope, when a customer request is posted twice with one idempotency key, then one ticket and first PUBLIC comment exist and the original 201 response is replayed.
- Given an internal-work-item create without requester, then it has no fabricated requester and its first comment is INTERNAL with IntegrationClient attribution.
- Given a key lacking an operation scope or outside group/kind/field/IP constraints, then the request is denied without mutation.
- Given a matching ETag, update succeeds and returns a new ETag; a stale ETag returns 412 with current ETag/version.
- Given two concurrent identical commands, exactly one ticket/comment/audit is committed.
- Given the same key with a different canonical request, then 409 is returned and no second mutation occurs.
- Given a trusted proxy, its forwarded client is evaluated; given an untrusted peer, forwarded headers cannot bypass the network boundary.
- Given a customer ticket with INTERNAL comments, customer reads never return INTERNAL content.

## Failure matrix

| Failure | HTTP/result | Persistence semantics |
|---|---|---|
| network source outside allowlist | 403 | security denial only; no business read/write |
| malformed/expired/revoked credential | generic 401 | authentication failure audit; no business read/write |
| missing scope/resource constraint | 403 | security denial; no success access event |
| per-client window exhausted | 429 + rate headers/Retry-After | rate denial audit; retry reuses the same idempotency key |
| malformed request/header | RFC 9457 400 | no mutation |
| same key, different canonical request | 409 | original receipt/mutation unchanged; misuse audit contains no raw key |
| same key still IN_PROGRESS | 409 + Retry-After | no second mutation |
| stale If-Match | 412 + current ETag/version | `FAILED_FINAL` receipt replays the same problem; no ticket audit |
| deterministic command validation | 400 | `FAILED_FINAL` receipt when validation occurs inside the command boundary |
| ticket/access/security audit failure | 503/fail closed | business mutation/read success and receipt roll back |
| receipt persistence failure | 503 | business mutation and canonical audit roll back together |

## Validation

- `./gradlew test` in `backend/` with PostgreSQL Testcontainers.
- `npm test`, `npm run build`, `npm run test:e2e` where the existing full gate requires frontend regression coverage.
- OpenAPI parse and operation/negative-surface contract tests.
- Secret scan of captured application output and canonical audit/idempotency rows.

## Compatibility and migration

- OpenAPI classification: new private v1 surface; no Core API breaking change.
- Forward migration adds `INTERNAL_WORK_ITEM`, nullable requester, IntegrationClient comment author, and idempotency receipts.
- No backfill is needed. Existing tickets retain non-null requesters and existing kind values.
- Rollback is forward-fix or backup/restore after confirming no new Platform records; destructive down migration is not shipped.

## Human explanation

- Platform HTTP/auth/rate-limit concerns live in a separate adapter, while Ticketing owns the aggregate transaction and canonical change audit.
- PostgreSQL transaction and unique identity are sufficient for exactly-one committed outcome in the supported single-database topology.
- Network placement is defense in depth; it never grants identity or ticket permission.
- A horizontally scaled deployment would require measured demand and a shared rate-limit implementation, not a change to the API contract.
