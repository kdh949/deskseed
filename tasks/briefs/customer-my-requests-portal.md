# Customer My Requests Portal — Stack F PR 3/3

## Goal

Magic-link로 인증된 고객이 자신의 문의만 목록/상세로 보고 PUBLIC follow-up을 남기며,
기존 익명 문의는 request access token 또는 티켓별 signed claim grant를 명시적으로 제시해 연결한다.

## Decision and source references

- Decision IDs: D-002, D-005, D-012, D-014, D-021, D-028, D-032, D-038, D-040, D-046
- Accepted ADRs: ADR 0029, ADR 0034
- Requirements: REQ-AUTH-002, REQ-TKT-003, REQ-TKT-004, REQ-TKT-005, REQ-TKT-008
- OpenAPI operations: `getCustomerAccessMode`, `listCustomerRequests`, `getCustomerRequest`,
  `addAuthenticatedCustomerComment`, `issueAnonymousRequestClaimGrant`,
  `claimAnonymousCustomerRequest`, `getCustomerAccessModeSetting`, `updateCustomerAccessModeSetting`
- Verification gates: AUTH-003, AUTH-004, TKT-002, CHN-005, CHN-006, CHN-007,
  ACC-007, UI-001, UI-002, UI-004, UI-005, MAIL-001

## Actor and source

- Portal read/comment/claim: authenticated CUSTOMER, source CUSTOMER_PORTAL.
- Anonymous claim-grant issue: request-access capability holder, source CUSTOMER_PORTAL;
  no email/token plaintext in logs or audit.
- Access-mode change: ADMIN staff, source ADMIN_UI.
- Customer resource constraint: current session account's `customer_id` must equal the ticket requester.
- Claim does not derive ownership from email equality. Email match is only a necessary condition after
  a ticket-specific capability has been proven.

## Product and UX contract

- Screens/routes: PUB-004 `/account/requests`, `/account/requests/:ticketNumber`;
  claim panel on account request list; ADM-004 `/admin/access/customer-mode`.
- List/detail responses are explicit customer projections. They contain no INTERNAL comment, child relation,
  staff assignment, audit ID/event, delivery metadata, or staff-only field.
- Loading, empty, generic denied/not-found, server error, and expired/used claim states are distinct.
- Follow-up draft survives failure. Success invalidates the owned-request list/detail queries.
- Keyboard focus moves to mutation status and all status meaning has text in addition to color.
- Visual evidence: customer portal at 1280 and 1440; responsive behavior remains usable at 390.

## In scope

- V20 claim-grant lifecycle and settings optimistic version migration.
- Authenticated customer list/detail/follow-up endpoints and customer-safe SQL projection.
- Explicit claim with existing request access token or short-lived HMAC-signed grant.
- Claim grant exchange protected by the existing request access token.
- Customer access-mode public contract and audited ADMIN GET/PUT contract.
- Portal and admin UI, PostgreSQL integration tests, Playwright/axe/visual E2E, docs/OpenAPI sync.

## Out of scope

- Organization-shared requests, password/SSO/social login.
- Automatic SOLVED reopen policy; customer follow-up on SOLVED/CLOSED returns conflict.
- Production email provider, inbound email, bounce, attachments, and rich text.

## Invariants and failure semantics

- An authenticated list/detail query starts with `tickets.requester_id = principal.customerId` and
  `kind = CUSTOMER_REQUEST`; ticket-number guessing and cross-account access return the same 404.
- Follow-up is always PUBLIC and append-only. Comment, optional PENDING→OPEN transition, one TicketAudit,
  ordered events, and stable acknowledgement mail intent commit or roll back together.
- A claim is valid only while the ticket is still owned by an unverified requester whose normalized email
  matches the authenticated account and one ticket-specific proof is active.
- Successful claim changes existing ticket requester ownership, revokes request access tokens, consumes the
  signed grant when used, appends REQUESTER_CHANGED audit, and writes security audit in one transaction.
- Invalid/expired proof is generic not-found; different-account claim is denied without disclosing ownership;
  consumed/replayed proof cannot transition a second time.
- Signed grants are HMAC authenticated, ticket-bound, email-fingerprint-bound, default 15-minute TTL, and
  stored only as a SHA-256 token digest with single-use state.
- ADMIN access-mode update uses expected version. Setting and CUSTOMER_ACCESS_MODE_CHANGED audit commit or
  roll back together.
- ANONYMOUS_ALLOWED and REGISTRATION_OPTIONAL accept anonymous submit. REGISTRATION_REQUIRED requires an
  authenticated customer; existing capability-protected detail remains viewable for compatibility.

## Data and privacy

- New data: claim token digest, ticket/customer references, keyed email fingerprint, expiry/consume timestamps.
- Claim/access/session/CSRF plaintext is never persisted, logged, audited, exported, or placed in URLs.
- Customer responses expose support content only through the owned PUBLIC projection and use `Cache-Control: no-store`.
- Mail intent keeps the existing protected/body-minimized outbound policy and stable idempotency key.

## Acceptance scenarios

1. Customer A lists/reads only A-owned tickets; Customer B, guessed number, child, INTERNAL and audit data are absent.
2. A valid request token claims exactly one matching-email anonymous ticket; wrong token, replay and different email fail.
3. A valid signed grant claims once; tamper, expiry, replay and different email fail without secret leakage.
4. Each access mode enforces anonymous/authenticated submit and view contracts; ADMIN change is audited atomically.
5. A customer PUBLIC follow-up appears once in staff conversation and ticket audit and creates one stable mail intent;
   retry or client replay does not duplicate the comment or mail.
6. Portal loading/empty/error/denied/expired states pass keyboard and axe checks at required desktop widths.

## Validation

- Backend targeted PostgreSQL tests, migration upgrade test, architecture/module test, full `./gradlew test`.
- Frontend unit/typecheck/build, Playwright customer portal tests, axe, 1280/1440 visual snapshots.
- Compose production-shaped smoke and Mailpit API recipient/subject/link/no-duplicate check.

## Compatibility and migration

- OpenAPI additions are additive. Existing anonymous submit/detail contracts remain compatible.
- V20 is forward-only and adds claim-grant state plus settings version without rewriting ticket ownership.
- Rollback disables new routes/UI; schema remains for forward-fix. Existing tokens and sessions remain valid.

## Human explanation

- Ticket ownership remains one requester ID on the current Ticket row; claim is an explicit audited transfer,
  not an email-driven merge.
- PostgreSQL transaction + row lock + unique token digest is sufficient for single-use behavior; no broker/cache is needed.
- If claim or portal query contention/latency is measured beyond PostgreSQL limits, revisit indexing or work claiming,
  not the ownership model.
