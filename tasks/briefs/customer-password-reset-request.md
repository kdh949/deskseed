# Customer password reset request

## Goal

고객이 계정 존재 여부를 노출하지 않는 동일한 응답으로 password reset proof를 요청하고, active password 계정에만
30분 single-use proof가 든 protected mail을 받는다.

## Decision and source references

- Decisions: D-046, D-057, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirements: REQ-AUTH-003
- Plan/API: docs/56 Task 10, `requestCustomerPasswordReset`
- Gates: AUTH-007, MAIL-001, MAIL-002, ARCH-004, DOC-001

## Actor and source

- Actor는 credential 확인 전 `SYSTEM`, source는 `CUSTOMER_PORTAL`이다.
- Staff role, integration scope, 임의 actor header를 받지 않는다.
- Resource constraint는 정규화 email advisory lock 아래 조회한 active password account 한 개다.
- Request/correlation context는 token, mail intent, required security audit에 함께 전달한다.

## Product and API contract

- `POST /api/v1/customer/auth/password-reset-requests`는 syntactically valid email에 generic `202`와 최소 응답 지연을 적용한다.
- Unknown, passwordless, disabled identity에도 같은 body/header를 반환하며 mail/token 생성 여부를 노출하지 않는다.
- 목적·destination·network 제한 초과는 `Retry-After`가 있는 `429`, limiter/DB/outbox/audit 실패는 generic `503`이다.
- `requestCustomerPasswordReset`는 runtime parity 증거와 함께 `FROZEN`으로 승격한다.

## In scope

- password-reset-request Redis limiter와 content-free fingerprint audit
- active password account eligibility를 account-email lock 아래 판정
- 30분 `PASSWORD_RESET` digest token과 protected outbox intent의 원자적 enqueue
- enumeration, throttling, unavailable, outbox/audit rollback, secret-output 통합 테스트
- request endpoint security allowlist와 OpenAPI runtime freeze

## Out of scope

- token consume, password hash 교체, credential-version 증가, 기존 session revoke
- reset frontend route, provider별 delivery 변경, MFA/SSO/social login
- supported-deployment limiter capacity 측정

## Invariants and failure semantics

- Limiter allowance는 DB transaction 전에 소비하며 adaptive hash나 SMTP network I/O를 실행하지 않는다.
- Token은 `PASSWORD_RESET` purpose, account ID, normalized email에 묶이고 raw value 대신 SHA-256 digest만 저장한다.
- Eligibility가 없는 identity도 metadata-only `CUSTOMER_PASSWORD_RESET_REQUESTED` success audit 한 건을 남긴다.
- Token, protected mail intent, required audit는 한 transaction에서 commit/rollback한다.
- Outbox worker의 delivery failure는 이미 committed request transaction을 rollback하지 않는다.
- Request 재시도는 새 proof 발급 시도이며 Redis limiter와 30분 expiry로 제한한다.

## Data and privacy

- Normalized email, account status, password-hash 존재 여부만 eligibility에 사용하며 hash 값은 읽거나 비교하지 않는다.
- Raw reset token은 protected mail ciphertext 안에만 있고 ordinary outbox/audit/log에는 없다.
- Email/account 존재 여부/password 상태는 response, audit metadata, ordinary log에 없다.
- Token/outbox/audit retention은 기존 credential, protected-mail, admin-security 정책을 따른다.

## Threats changed

- Enumeration은 동일 `202`, 동일 header, 최소 응답 지연으로 완화한다.
- Request flooding은 Redis atomic expiring purpose/destination/network limiter로 제한한다.
- Proof confusion은 purpose-bound token과 전용 mail template로 막는다.
- Audit/outbox persistence failure는 token 발급까지 rollback해 추적 불가능한 proof를 남기지 않는다.

## Acceptance scenarios

- Given active password account, when reset is requested, then one protected mail and one 30-minute digest proof are atomically persisted.
- Given unknown, passwordless, or disabled identity, when reset is requested, then the same `202` is returned and no proof/mail is created.
- Given exhausted destination budget, when another request arrives, then generic `429` includes `Retry-After` and a denied audit.
- Given unavailable limiter, when a request starts, then generic `503` occurs before token work.
- Given outbox or required audit failure, when an eligible request runs, then generic `503` leaves no token, mail, or audit row.

## Validation

- focused `CustomerPasswordResetIntegrationTest`
- focused `CustomerAuthenticationLimiterUnavailableIntegrationTest`
- focused `ApiDocumentationIntegrationTest`
- `./gradlew --no-daemon fastTest contractTest integrationTest`
- `make docs-check`
- `git diff --check` and secret/output scans
- AUTH-006/ADR 0043 supported-deployment capacity target: `Not run`

## Compatibility and migration

- Uses V81 credential/token schema and V82 protected mail template without a new migration or backfill.
- Existing registration, password login, and passwordless endpoints are unchanged.
- Request operation becomes `FROZEN`; consume remains blueprint-only until the next child PR.
- Rollback is application-only; already issued tokens expire within 30 minutes and remain unusable without the consume endpoint.

## Human explanation

- Account-email locking keeps eligibility and proof issuance ordered with registration/reset changes without holding a lock during network work.
- Durable protected outbox reuse avoids a second secret store and keeps SMTP outside the transaction.
- Content-free fingerprints preserve abuse/audit correlation without retaining raw email in security metadata.
