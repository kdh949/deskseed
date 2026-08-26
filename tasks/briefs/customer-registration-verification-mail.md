# Customer registration verification mail foundation

## Goal

EMAIL_VERIFICATION proof를 로그인 링크와 구분된 versioned protected mail로 durable outbox에 저장하고,
후속 registration request flow가 SMTP network call 없이 한 transaction에 enqueue할 수 있게 한다.

## Decision and source references

- Decisions: D-046, D-057
- Accepted ADRs: ADR 0029, 0042
- Requirements: REQ-AUTH-003, REQ-NOTIF-001
- Plan: docs/49 outbound foundation, docs/56 Task 8
- Gates: AUTH-002, AUTH-005, MAIL-001, MAIL-002, ARCH-004, OPS-001

## Actor/source and boundaries

- Future actor/source: SYSTEM on behalf of `CUSTOMER_ANONYMOUS`, `CUSTOMER_PORTAL`
- No new HTTP surface or permission in this slice.
- Mail body is `PROTECTED`; raw verification link is encrypted at rest and ordinary columns keep the canonical placeholder.
- Provider delivery remains post-commit through the existing worker/retry/dead-letter path.

## In scope

- `CUSTOMER_REGISTRATION_VERIFICATION` template/version and protected content type
- dedicated Korean subject/body and absolute HTTPS/HTTP URL validation through existing mail safety
- V81.1 constraint expansion between authoritative V81 and future V82
- clean/upgrade migration, renderer, secret-string, durable ciphertext tests

## Out of scope

- token issuance, recipient eligibility, registration endpoint, continuation cookie, verification consume
- SMTP provider changes, delivery retry changes, HTML template

## Invariants and failure semantics

- Registration verification is never classified or rendered as a login link.
- Raw link does not appear in content `toString`, ordinary `text_body`, audit, or log.
- `OutboundMailPort.enqueue` remains caller-transactional; encryption or insert failure rolls back the caller mutation.
- Existing CUSTOMER_MAGIC_LINK/REQUEST_RECEIVED/PUBLIC_AGENT_REPLY rows and keys remain valid through V81.1.

## Validation

- focused `OutboundMailPolicyTest`
- focused `CustomerRegistrationMailMigrationTest`
- focused protected outbox persistence in `OutboundMailDeliveryIntegrationTest`
- `./gradlew fastTest contractTest migrationTest`
- `make docs-check`
- `git diff --check`

## Compatibility and rollback

- OpenAPI/UI: unchanged.
- V81.1 is additive to the template allowlist and preserves all existing rows.
- Application rollback can leave the new allowlisted value unused. Applied Flyway history is retained; recovery is
  backup restore or a reviewed forward fix, never a down migration/checksum edit.

## Human explanation

- A distinct template prevents registration activation from being mistaken for passwordless login in operations or content.
- A point migration keeps future authoritative V82 available for request-form binding.
- No performance benchmark is relevant to this schema/render slice.
