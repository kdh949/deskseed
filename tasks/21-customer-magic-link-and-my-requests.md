# Codex Brief 21 — Customer Magic Link and My Requests

## Goal

Let a customer sign in with a single-use email magic link, list their verified requests, claim an anonymous request safely, and add public follow-ups.

## Requirements

REQ-AUTH-001, REQ-AUTH-002, REQ-TKT-003 through REQ-TKT-005.

## In scope

- DB-backed one-time token service; 15-minute configurable TTL.
- enumeration-safe magic-link request endpoint.
- Mailpit/outbound-mail delivery.
- secure customer session and logout.
- CustomerAccount creation/linking.
- My Requests list/detail/public follow-up.
- explicit anonymous request claim using request token or signed claim flow.
- admin access modes ANONYMOUS_ALLOWED, REGISTRATION_OPTIONAL, REGISTRATION_REQUIRED.
- auth, claim, and settings audit.

## Out of scope

Password login, social login, organization-shared requests, SSO.

## Acceptance

Single-use/expiry/replay tests, email enumeration tests, cross-customer isolation, no INTERNAL/child/audit leak, and claim-without-email-auto-link regression.

## Required verification IDs

`AUTH-001`, `AUTH-002`, `AUTH-003`, `AUTH-004`, `TKT-002`, `ACC-007`, `MAIL-001`.

## Stack F PR 2/3 implementation slice

This PR implements only the authentication foundation from this broader brief.
My Requests, public follow-up, access-mode administration and explicit ticket claim
remain later slices and must not be inferred from email equality.

### Decision and contract references

- Requirements: `REQ-AUTH-001`, `REQ-AUTH-002` authentication foundation.
- Decision IDs: `D-002`, `D-005`, `D-038`, `D-039`, `D-046`.
- Accepted ADR: `0029-email-magic-link-customer-authentication.md`.
- OpenAPI operations: `requestCustomerMagicLink`, `consumeCustomerMagicLink`,
  `getCustomerCsrfToken`, `deleteCustomerSession`, `getCurrentCustomer`.
- Verification gates: `AUTH-001`, `AUTH-002`, `AUTH-003`, `MAIL-001`.

### Actors and data boundaries

- Request/failure/rate-limit: `SYSTEM` actor, `CUSTOMER_PORTAL` source and bounded
  request/correlation context; destination appears only as a keyed fingerprint in
  security audit/throttle state.
- Consume success/account link/logout: verified `CUSTOMER` actor and account/customer
  identifiers; token, cookie and CSRF values are absent from audit and logs.
- The canonical admin/security ledger is extended for customer-auth security events;
  it remains separate from ticket change, access/search and mail-delivery ledgers.

### Invariants and failure semantics

- A syntactically valid request returns the same 202 shape for known, unknown and
  rate-limited destinations and is paced into the same timing class.
- Rate limiting intersects normalized-destination and requester-network fingerprints.
- The Spring Security `OneTimeTokenService` contract is implemented by a PostgreSQL
  adapter that stores only a SHA-256 verifier for a 256-bit random token.
- Atomic consume changes one unexpired row from unused to consumed; replay, expiry,
  malformed input and a concurrent loser create no session.
- Token consume, CustomerAccount creation/link, new session digest and required
  security audit commit or roll back together.
- A successful login always rotates any presented customer session. Logout requires
  a customer-session-bound CSRF header and revokes server state.
- Existing anonymous tickets remain owned by their original unverified Customer row.
  Creating a verified CustomerAccount never rewrites ticket requester ownership.
- Outbound mail is a durable intent in the same token-request transaction; SMTP stays
  post-commit through the Stack F PR 1/3 worker.

### Privacy, retention and threats

- Magic-link TTL defaults to 15 minutes and is configurable only within 5–60 minutes.
- Token/cookie/CSRF plaintext is never stored, logged, audited, placed in analytics or
  sent as a URL query. The email uses a frontend fragment; the consume page removes it
  from history before calling the JSON-body consume endpoint.
- Token and throttle rows are short-lived authentication/security data. This slice
  supplies bounded cleanup indexes/seams but not a general retention administration UI.
- Changed threats: email enumeration, token replay/race, session fixation, CSRF,
  cross-customer principal confusion, Referer/log leakage and audit bypass.

### Explicit exclusions

Password/social login, historical ticket auto-claim, My Requests, public follow-up,
claim endpoint, production mail provider, inbound mail and URL-query token handling are
not implemented in this PR.
