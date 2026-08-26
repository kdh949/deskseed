# Customer registration verification

## Goal

고객이 이메일 `EMAIL_VERIFICATION` proof와 같은 pending intent의 browser continuation cookie를 함께 제출할 때만
verified profile, password account, registration consent acceptances를 한 번 원자적으로 활성화한다.

## Decision and source references

- Decisions: D-057, D-058, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirements: REQ-AUTH-003, REQ-CONSENT-002
- Plan/API: docs/56 Task 8, `verifyCustomerRegistration`
- Gates: AUTH-005, CONSENT-002, ARCH-004, CFG-006, DOC-001

## Actor and source

- Before activation, invalid/denied attempts use `SYSTEM`; successful proof consumption establishes the new `CUSTOMER` actor.
- Source is `CUSTOMER_PORTAL`; no staff role, authenticated customer session, or external scope is accepted.
- Resource constraint is the exact token-bound registration intent plus its cookie digest and current `REGISTRATION` policy versions.
- Request/correlation context is server-owned and shared by account, acceptance, and security audit effects.

## Product and API contract

- `POST /api/v1/customer/registration-verifications` consumes a body token plus HttpOnly continuation cookie.
- Success returns `204`, no-store/no-referrer headers, and expires the continuation cookie without creating a session.
- Invalid/missing/mismatched/expired/replayed/wrong-purpose proof returns generic `401`.
- Existing account or changed policy state returns generic `409`; limiter or required persistence failure returns generic `503`.
- This backend slice has no frontend route or visual behavior.

## In scope

- purpose-bound verification throttling with token/network HMAC fingerprints
- shared normalized-email account advisory lock
- atomic token consume, continuation proof lock, current-policy revalidation, customer/password account creation
- append-only registration acceptances and metadata-only `CUSTOMER_REGISTRATION_VERIFIED` / `CUSTOMER_CONSENT_ACCEPTED` audits
- cookie expiry, concurrency/replay/conflict/audit-failure/secret-output integration tests
- customer root profile creation with bounded optional company name

## Out of scope

- authenticated session creation; the customer signs in through the later password-login operation
- password login/reset, passwordless registration completion, anonymous ticket claim
- frontend verification UI, SMTP provider/retry changes, legal-text seeding

## Invariants and failure semantics

- Email token alone never activates a password; token and continuation digest must resolve to the same unexpired pending intent.
- Request and verification use the same `customer-account:<normalized-email>` transaction advisory key.
- Token purpose mismatch never consumes another token type; invalid proof rollback preserves a valid token for the correct browser.
- Current required policies are revalidated at final activation; stale/archived/new-required state creates no account.
- Existing-account races never replace password, profile, or consent state.
- Customer, password account, acceptances, intent/token consumption, and both audit kinds commit or roll back together.
- Concurrent verification has one winner and one generic invalid replay result.
- Redis allowance remains outside the database transaction; failure consumes allowance but does not leave partial activation.

## Data and privacy

- Verified profile stores display name, company name, normalized/display email, Argon2id hash, and server verification time.
- Append-only acceptance references immutable policy ID/version and account/customer only; no policy body is duplicated.
- Raw token/cookie/password/hash/company/email never enters routine audit metadata or ordinary log output.
- No session, ticket, webhook, export, or staff-only projection is created or expanded.

## Acceptance scenarios

- Matching token/cookie activates one verified password account, one acceptance per selected policy, and expires the cookie.
- Mismatch, wrong purpose, expiry, replay, or missing cookie returns `401`, keeps valid proof state when retry is possible, and creates no account.
- Current policy change or an account created during the flow returns `409` without replacing identity data.
- Concurrent consumes produce statuses `204` and `401` with exactly one account/acceptance.
- Required audit failure returns `503` and rolls customer/account/acceptance/token/intent effects back.
- Redis unavailable returns `503` before proof consumption.

## Validation

- focused registration/verification HTTP integration tests with PostgreSQL and Redis
- focused unavailable-limiter integration test
- Task 8 head: fast, contract, integration, Mailpit, docs checks
- Checkpoint C final head: full plan gate including authentication concurrency and Mailpit flow

## Compatibility and migration

- Uses V80/V81/V81.1 without a new migration or backfill.
- `verifyCustomerRegistration` becomes `FROZEN` only with runtime parity; later identity operations remain unfrozen.
- Application rollback leaves protected pending intents/tokens for expiry or a reviewed forward fix; no schema rollback applies.

## Human explanation

- Both proofs are required to prevent a victim email link from activating a password selected in another browser.
- One account lock coordinates request, verification, and passwordless account creation without holding it during Argon2id work.
- Registration verification intentionally does not authenticate a session; password login remains an explicit audited command.
