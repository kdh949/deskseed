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
