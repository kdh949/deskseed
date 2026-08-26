# Customer registration request

## Goal

익명 고객이 현재 등록 동의 정책과 password/profile을 제출하면 계정 존재 여부를 드러내지 않는 `202`와
브라우저 귀속 continuation cookie를 받고, 신규 이메일에만 digest proof와 protected verification mail intent가 원자적으로 생성된다.

## Decision and source references

- Decisions: D-057, D-058, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirements: REQ-AUTH-003, REQ-CONSENT-002, REQ-NOTIF-001
- Plan/API: docs/56 Task 8, `requestCustomerRegistration`
- Gates: AUTH-005, CONSENT-002, MAIL-001, MAIL-002, ARCH-004, CFG-006, DOC-001

## Actor and source

- Actor/source: anonymous customer request represented by `SYSTEM`, `CUSTOMER_PORTAL`.
- Required role/scopes: public operation; no staff role, customer session, or external scope grants access.
- Resource constraint: submitted policy keys must resolve to the current `REGISTRATION` projection and every current required policy appears exactly once.
- Request/correlation context comes from the trusted server request context and is copied to intent, token, mail intent, and audit.

## Product and API contract

- `POST /api/v1/customer/registrations` implements the committed customer identity contract.
- New, existing, passwordless, and pending email states share the same `202`, no-store headers, and 43-character continuation-cookie shape.
- Invalid/stale consent input returns stable `400`; exhaustion returns generic `429`; required limiter/policy/persistence failure returns generic `503`.
- This backend slice adds no frontend route or visual behavior.

## In scope

- strict email/profile/password input validation and current-policy resolution
- purpose-bound Redis throttling before adaptive hashing or transactional database work
- real Argon2id work for both new and existing accounts
- normalized-email advisory locking and account-existence-safe pending intent replacement
- `EMAIL_VERIFICATION` digest token, protected registration mail outbox intent, metadata-only security audit
- HttpOnly, Secure, SameSite=Lax, path-scoped continuation cookie
- policy, enumeration, rollback, unavailable-limiter, secret-output integration tests

## Out of scope

- verification proof consume, customer/account/consent activation, registration-cookie expiry response
- password login, reset, passwordless-only magic-link changes, current-customer projection
- SMTP network delivery, frontend UI, production legal-text seeding

## Invariants and failure semantics

- Registration intent stores only Argon2id password hash and continuation digest; one-time token storage contains only a digest.
- Existing accounts execute the same policy/password work but create no intent, token, or mail intent and receive an indistinguishable dummy continuation proof.
- Current required policies are fail-closed; duplicate, unknown, wrong/stale version, or missing required selection is rejected.
- The account check and pending-intent replacement share one normalized-email advisory-lock transaction.
- Intent, token, durable mail intent, and `CUSTOMER_REGISTRATION_REQUESTED` audit commit or roll back together.
- Redis allowance is intentionally outside that transaction and remains consumed after database/audit/outbox failure.
- Provider delivery remains post-commit; the registration request itself has no network mail call.

## Data and privacy

- Written PII: pending email/profile only for an eligible new-account registration; existing-account input is not persisted by this flow.
- Password, raw email token, and continuation proof never enter ordinary logs/audit or plaintext database columns.
- Protected verification content is encrypted at rest; ordinary mail body stores the canonical protected placeholder.
- Registration consent selection is immutable intent evidence; final append-only account acceptance belongs to verification consume.
- No ticket, webhook, export, or staff projection is expanded.

## Acceptance scenarios

- Given a new email and all current required policies, when registration is requested, then the server returns generic `202`, stores protected proofs/outbox, and appends one metadata-only audit.
- Given an existing account, when the same valid request is submitted, then the response/cookie shape is the same and no registration artifact or mail is created.
- Given stale, unknown, duplicate, or unavailable required policy state, then no registration artifact is persisted.
- Given exhausted Redis allowance, then `429` includes `Retry-After` without account state or PII.
- Given Redis unavailable, then `503` occurs before database registration work.
- Given audit or outbox insert failure, then `503` is returned and intent/token/outbox/audit all roll back while the allowance stays consumed.

## Validation

- focused `CustomerAuthPropertiesTest`
- focused `CustomerRegistrationRequestIntegrationTest`
- focused `CustomerAuthenticationLimiterUnavailableIntegrationTest`
- Task 8 head: fast, contract, integration, Mailpit, docs checks
- Checkpoint C final head: full AUTH-005/006/007/008 and plan-wide checkpoint gates

## Compatibility and migration

- Implemented `requestCustomerRegistration` is marked `FROZEN`; unimplemented identity operations remain unfrozen. Customer Identity is a separately owned contract, so the Core bundle has no generated diff and its parity is still checked.
- Uses V81/V81.1 structures without another migration or backfill.
- Application rollback leaves no new schema. Existing pending rows remain protected and can expire/cancel under the documented retention path.

## Human explanation

- Argon2id runs before account eligibility is revealed and outside shared database/Redis work, bounding both enumeration and transaction contention.
- A browser-held continuation proof prevents the email link alone from activating a password chosen by a different browser; activation is intentionally deferred to the next stacked PR.
- Redis remains the single limiter authority selected by D-060; supported-deployment capacity targets remain `Not run` in this slice.
