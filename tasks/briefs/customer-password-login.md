# Customer password login

## Goal

검증 완료된 password 고객이 계정 존재·상태·credential 종류를 외부에 구분해 노출하지 않으면서 기존 세션을 교체하고
bounded `CurrentCustomer` projection으로 로그인한다.

## Decision and source references

- Decisions: D-057, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirements: REQ-AUTH-003
- Plan/API: docs/56 Task 9, `createCustomerPasswordSession`, `getCurrentCustomer`
- Gates: AUTH-006, ARCH-004, DOC-001

## Actor and source

- Credential 검증 전 실패·제한 이벤트 actor는 `SYSTEM`; 성공 후 actor는 검증된 `CUSTOMER`다.
- Source는 `CUSTOMER_PORTAL`이며 staff role, integration scope, 임의 actor header를 받지 않는다.
- Resource constraint는 정규화 email로 잠근 하나의 customer account와 제출된 current-session cookie다.
- Request/correlation context는 limiter 뒤의 필수 security audit와 새 세션에 동일하게 적용한다.

## Product and API contract

- `POST /api/v1/customer/auth/password-sessions`는 email/password를 받고 성공 시 `200 CurrentCustomer`와 새 세션 cookie를 반환한다.
- Unknown, wrong password, disabled, passwordless, incomplete registration은 같은 `401` problem을 반환한다.
- 목적·destination·network 제한 초과는 `Retry-After`를 포함한 `429`, Redis/DB/audit 실패는 generic `503`이다.
- `GET /api/v1/customer/me`는 company, credential, registration, available-method 상태를 bounded projection으로 반환한다.
- 이 backend slice에는 frontend route나 시각 변경이 없다.

## In scope

- password-login Redis limiter와 content-free fingerprint audit
- real-or-dummy Argon2id 비교
- account email lock 아래 credential snapshot 재검증, current-session rotation, password session 생성
- session credential-version binding과 현재 고객 credential/profile projection
- generic failure, rate limit, limiter unavailable, audit rollback, secret-output 통합 테스트
- `createCustomerPasswordSession` runtime parity와 계약 freeze

## Out of scope

- password reset, passwordless registration completion, magic-link password-account 제한
- MFA, SSO, social login, anonymous ticket claim
- supported-deployment burst/capacity 측정과 frontend 로그인 화면

## Invariants and failure semantics

- Limiter는 account 조회와 adaptive hashing 및 DB transaction 전에 allowance를 소비한다.
- 모든 syntactically valid credential 시도는 저장 hash 또는 process-local dummy hash에 대해 adaptive 비교를 정확히 한 번 수행한다. 저장 hash의 Argon2id envelope/parameter/salt/hash 길이가 사용할 수 없으면 비교 전에 dummy를 선택한다.
- 성공 전 normalized-email advisory lock 아래 status/hash/credential version을 다시 확인한다.
- 새 session, 이전 current session revocation, `last_login_at`, 성공 security audit는 함께 commit/rollback한다.
- 성공 session은 `PASSWORD` authentication method와 현재 credential version snapshot에 묶인다.
- Credential version이 달라진 기존 session은 즉시 인증되지 않는다.
- 실패 event는 account 존재·상태를 반영하지 않는 동일 fingerprint metadata만 남긴다.
- 재시도는 새 로그인 시도이며 idempotent하지 않다. 외부 network I/O는 없다.

## Data and privacy

- Login은 normalized email, account status, Argon2id hash, credential version과 bounded profile을 읽는다.
- DB에는 새 opaque session digest와 authentication method/version snapshot만 저장한다.
- Password/hash/raw cookie/email/company name은 response의 허용된 본인 profile 외 routine audit/log metadata에 남지 않는다.
- Session은 기존 idle/absolute expiry와 revocation retention을 그대로 사용한다.

## Threats changed

- Enumeration은 generic response와 real-or-dummy adaptive work로 완화한다.
- Brute force는 Redis atomic expiring counters와 generic `429`로 제한한다.
- Credential reset 뒤 replay는 session credential-version binding으로 거부한다.
- Audit failure는 authenticated success를 반환하지 않고 session rotation을 rollback한다.
- Raw secret와 PII는 limiter key, audit metadata, ordinary log에서 제외한다.

## Acceptance scenarios

- Given ACTIVE password account, when correct password is submitted, then one audited password session replaces the current cookie.
- Given unknown/wrong/disabled/passwordless/incomplete identity, when credentials are submitted, then one generic `401` follows adaptive work.
- Given exhausted destination budget, when another attempt arrives, then generic `429` contains bounded `Retry-After`.
- Given unavailable limiter, when login starts, then generic `503` occurs before credential/session DB work.
- Given audit persistence failure, when otherwise-valid credentials are submitted, then generic `503` leaves the old session active and creates no new session.
- Given a credential-version change, when an old session is resolved, then authentication fails immediately.

## Validation

- `./gradlew --no-daemon fastTest contractTest integrationTest`
- focused password-login and limiter-unavailable PostgreSQL/Redis integration tests
- `make docs-check`
- `git diff --check` and secret/output scans
- AUTH-006 supported-deployment burst/capacity target: `Not run` by the approved checkpoint decision

## Compatibility and migration

- Uses V81 session/account columns without a new migration or backfill.
- Existing magic-link sessions retain `MAGIC_LINK` and credential-version `0`; new resolution additionally rejects stale version snapshots.
- `createCustomerPasswordSession` becomes `FROZEN`; reset and passwordless completion operations remain unfrozen.
- Rollback is application-only; created password sessions use the existing server-revocable schema.

## Human explanation

- Adaptive hashing stays outside the shared DB transaction so expensive password work cannot hold account locks.
- The account is rechecked under the same normalized-email lock before session issuance so reset/disable races fail closed.
- Redis remains the single limiter adapter selected by ADR 0043; measured capacity evidence, not a second store, would change sizing.
