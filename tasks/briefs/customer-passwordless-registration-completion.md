# Customer passwordless registration completion

## Goal

인증된 passwordless magic-link 고객이 current registration consent와 profile, 새 password를 제출해
password account 등록을 완료한다. 완료 mutation은 기존 session을 모두 폐기하고 password 인증 방식의 새
session으로 rotate하며, 이메일 일치만으로 기존 익명 ticket ownership을 변경하지 않는다.

## Decision and source references

- Decisions: D-057, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirements: REQ-AUTH-002, REQ-AUTH-004, REQ-CONSENT-002
- Plan/API: docs/56 Task 11, `completePasswordlessCustomerRegistration`, `getCurrentCustomer`
- Gates: AUTH-002, AUTH-003, AUTH-004, AUTH-008, CONSENT-002, ARCH-004, DOC-001

## Actor and source

- 성공 command actor는 현재 session의 `CUSTOMER`, source는 `CUSTOMER_PORTAL`이다.
- Customer session과 CSRF가 모두 필요하며 임의 actor header, staff role, integration scope를 받지 않는다.
- Resource constraint는 현재 account/customer/email에 귀속되고 current credential version을 snapshot한
  `MAGIC_LINK` session이다.
- Request/correlation context는 consent acceptance와 required security audit에 함께 전달한다.

## Product and API contract

- `PUT /api/v1/customer/me/registration`은 passwordless magic-link session, CSRF, 새 password, bounded
  display/company profile, current registration policy versions를 받는다.
- 성공은 `200 CurrentCustomer`와 secure rotated customer-session cookie를 반환한다. Projection은
  `credentialState=PASSWORD`, `registrationState=COMPLETE`, password authentication availability를 표시한다.
- Missing/stale session은 generic `401`, CSRF mismatch는 generic `403`, password account·stale policy·state
  race는 `409`, limiter 초과는 `429`, limiter/DB/consent/audit failure는 `503`이다.
- Completion과 current-customer operations는 runtime problem/projection parity를 갖춘 뒤 `FROZEN`으로 승격한다.

## In scope

- `REGISTRATION_COMPLETION` purpose-destination-network Redis limiter와 content-free fingerprint audit
- account/email/session lock 및 REGISTRATION consent-policy context lock의 고정 순서
- password hashing, bounded profile update, credential/account version 증가
- 모든 기존 session revoke와 `PASSWORD` session rotation
- current required policy validation, append-only acceptance, completion/consent security audit
- stable customer-session/CSRF problems와 current-customer projection parity

## Out of scope

- 이메일 일치 기반 anonymous ticket list/claim 또는 explicit claim proof 변경
- MFA, SSO, social login, frontend onboarding UI
- 새로운 schema migration 또는 기존 password account의 credential 변경
- Supported-deployment limiter capacity 측정

## Invariants and failure semantics

- Limiter allowance는 password hashing과 database transaction 전에 소비한다.
- Password hash는 limiter 허용 뒤 transaction 밖에서 계산하며 DB lock을 점유하지 않는다.
- Account/email/session을 먼저 잠근 뒤 REGISTRATION policy context를 잠가 registration verification과 같은
  lock ordering을 유지한다.
- Password/profile/credential version, consent acceptances, old-session revocation, new session과 required audit는
  하나의 transaction에서 commit/rollback한다.
- Current magic-link session의 credential snapshot이 stale하거나 이미 password account이면 password reset을
  우회하지 못하고 `409` 또는 인증 filter의 generic `401`을 반환한다.
- Required consent/audit persistence 실패는 mutation 전체를 rollback하지만 Redis allowance는 유지한다.
- Retry는 새 allowance를 소비하며 completion은 idempotent하지 않다. 성공 뒤 old session replay는 인증되지 않는다.

## Data and privacy

- Password는 customer-specific adaptive hash로만 저장하며 raw password/hash/session/CSRF는 response, audit,
  limiter metadata, ordinary log에 없다.
- Profile과 consent document body는 security audit에 없고, consent audit에는 policy ID/version/context만 남긴다.
- 기존 customer account/session/consent/audit retention과 export/webhook projection을 변경하지 않는다.

## Threats changed

- Passwordless-session 탈취 후 장기 재사용은 CSRF, current credential snapshot, session revoke/rotation으로 제한한다.
- Password-account registration bypass와 stale-policy acceptance는 locked account/policy 재검증으로 차단한다.
- Concurrent double completion은 account/session row lock과 conditional credential update로 하나의 winner만 허용한다.
- Anonymous ticket takeover는 customer email equality가 ticket requester ownership에 영향을 주지 않도록 차단한다.
- Required audit/consent bypass는 동일 transaction rollback으로 막는다.

## Acceptance scenarios

- Given a valid passwordless magic-link session, CSRF, current policies and bounded profile, when completion runs, then
  password/profile/consents/audits commit once, every old session is revoked, and one password session is returned.
- Given the old magic-link session after success, when a protected endpoint is requested, then stable generic `401` occurs;
  the new password can create a fresh password session.
- Given missing CSRF, a password account, stale policy or stale credential-version session, when completion is attempted,
  then stable `403`, `409`, or `401` occurs without partial mutation.
- Given a concurrent pair, when both try the same completion, then exactly one succeeds and one active password session remains.
- Given exhausted or unavailable limiter, when completion starts, then `429`/`503` occurs before credential work.
- Given consent or required audit persistence failure, when completion runs, then `503` rolls back profile, password,
  versions, acceptances, audits, revocations and the new session.
- Given an anonymous ticket with the same email, when registration completes, then its requester ownership is unchanged.

## Validation

- focused `CustomerPasswordlessRegistrationCompletionIntegrationTest`
- focused `CustomerAuthenticationLimiterUnavailableIntegrationTest`
- focused `ApiDocumentationIntegrationTest`
- `./gradlew --no-daemon fastTest contractTest integrationTest`
- `make docs-check` and bundle parity
- `git diff --check` and secret/output scans
- AUTH-006/ADR 0043 supported-deployment capacity target: `Not run`

## Compatibility and migration

- Reuses V81/V81.0.1 account/session/token identity binding without a new migration or backfill.
- Existing passwordless session rows remain readable; successful completion intentionally invalidates every pre-completion session.
- Existing password accounts retain password-reset-only credential replacement semantics.
- Rollback is application-only; a committed completion intentionally cannot restore revoked magic-link sessions.

## Human explanation

- Password hashing precedes database locking so adaptive credential work does not hold account or consent locks.
- Account then policy lock ordering serializes identity and policy changes consistently with registration verification.
- A new password session is created only inside the same transaction that revokes old sessions and records consent/audit, so
  callers never observe a partially completed account.
