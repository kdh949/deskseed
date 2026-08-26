# Customer password reset consume

## Goal

고객이 30분 single-use reset proof로 password를 정확히 한 번 교체하고, 기존 customer session 전체를 즉시 폐기한다.

## Decision and source references

- Decisions: D-057, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirement: REQ-AUTH-003
- Plan/API: docs/56 Task 10, `resetCustomerPassword`
- Gates: AUTH-007, ARCH-004, DOC-001

## Actor and source

- Proof 검증 전 실패·제한 actor는 `SYSTEM`, 성공 후 actor는 검증된 `CUSTOMER`다.
- Source는 `CUSTOMER_PORTAL`이며 staff role, integration scope, 임의 actor header를 받지 않는다.
- Resource constraint는 token에 귀속된 account ID와 normalized email이 가리키는 active password account다.
- Request/correlation context는 성공/실패 security audit에 전달한다.

## Product and API contract

- `POST /api/v1/customer/auth/password-resets`는 `PASSWORD_RESET` proof와 새 password를 받아 성공 시 `204`를 반환한다.
- 성공 response는 no-store/no-referrer이고 현재 browser session cookie를 즉시 만료한다.
- Unknown, wrong-purpose, expired, replayed, concurrent-loser proof는 같은 `401` problem을 사용한다.
- 목적·proof·network 제한 초과는 `Retry-After`가 있는 `429`, limiter/DB/audit 실패는 generic `503`이다.
- Endpoint는 authenticated session을 만들지 않으며 `resetCustomerPassword` runtime contract를 `FROZEN`으로 승격한다.

## In scope

- proof/network limiter와 content-free fingerprint audit
- Argon2id replacement hash를 limiter 뒤, DB transaction 밖에서 계산
- account-email lock 아래 proof atomic consume, password 교체, credential-version 증가
- 모든 기존 customer session revoke와 남은 reset proof 무효화
- expired browser cookie와 generic invalid-proof problem
- replay, wrong-purpose, expiry, rate limit, limiter failure, audit rollback, concurrent consume 통합 테스트

## Out of scope

- reset request/mail foundation은 parent PR 범위다.
- reset 성공 뒤 자동 로그인 또는 새 session 발급
- passwordless magic-link restriction/registration completion, MFA, SSO, social login
- frontend reset form과 supported-deployment limiter capacity 측정

## Invariants and failure semantics

- Limiter allowance를 먼저 소비하고 replacement Argon2id hash는 shared DB transaction/lock 밖에서 계산한다.
- Consumable target을 읽은 뒤 account-email lock을 먼저 잡고 token을 atomic consume해 같은/different proof 동시 소비의 deadlock과 double change를 막는다.
- Token purpose/account ID/normalized email이 locked account와 모두 일치해야 한다.
- Password hash, password-changed time, credential/account version, all-session revoke, reset-proof consume/invalidation, required success audit는 함께 commit/rollback한다.
- Invalid proof의 partial consume은 rollback한 뒤 별도 metadata-only denied audit를 남긴다.
- Required audit 실패는 generic `503`이며 credential, token, session 상태를 모두 rollback한다.
- 성공은 session을 만들지 않고 명시적 password login만 허용한다.

## Data and privacy

- Active account ID/email/status와 Argon2id hash를 갱신하고 session revocation/token consumption timestamps를 기록한다.
- Raw current/new password, raw proof, raw session cookie, email, company name은 audit metadata와 ordinary log에 없다.
- Limiter/audit에는 keyed proof/network fingerprint와 generic reason만 남는다.
- 기존 credential, token, session, admin-security retention을 재사용하며 export/webhook field를 추가하지 않는다.

## Threats changed

- Replay와 concurrent double rotation은 account serialization과 atomic token consume으로 차단한다.
- Credential recovery 뒤 session replay는 explicit revocation과 credential-version mismatch 두 층으로 거부한다.
- Wrong-purpose proof confusion은 purpose-bound lookup/consume으로 거부한다.
- Required audit bypass는 reset mutation 전체 rollback으로 막는다.
- Credential/hash/token/cookie leakage는 protected DTO/string와 metadata-only audit로 방지한다.

## Acceptance scenarios

- Given one valid reset proof and multiple sessions, when reset succeeds, then password/version/session revoke/token consume/audit commit once and the current cookie expires.
- Given a replayed, expired, wrong-purpose, or unknown proof, when reset is attempted, then generic `401` leaves credential and sessions unchanged.
- Given two valid reset proofs for one account, when consumed concurrently, then exactly one succeeds, both proofs become unusable, and credential version increases once without deadlock.
- Given unavailable limiter, when reset starts, then generic `503` occurs before hashing/proof/credential work.
- Given required audit persistence failure, when an otherwise-valid reset runs, then generic `503` leaves the proof reusable and every old session active.

## Validation

- focused `CustomerPasswordResetIntegrationTest`
- focused `CustomerAuthenticationLimiterUnavailableIntegrationTest`
- focused `ApiDocumentationIntegrationTest`
- `./gradlew --no-daemon fastTest contractTest integrationTest`
- `make docs-check`
- `git diff --check` and secret/output scans
- AUTH-006/ADR 0043 supported-deployment capacity target: `Not run`

## Compatibility and migration

- Uses V81 account/session/token columns without a new migration or backfill.
- Existing session rows remain schema-compatible; reset marks every active row revoked and increments the account credential version.
- Request operation remains unchanged; consume becomes `FROZEN` in this PR.
- Rollback is application-only; committed password resets intentionally cannot restore prior credentials or sessions.

## Human explanation

- Hashing before the transaction avoids holding PostgreSQL locks during adaptive credential work.
- Account lock before token consume gives one global order for multiple valid proofs and avoids token-row/account-lock inversion.
- Explicit revocation plus version binding makes recovery effects immediate and defense-in-depth without a new cache or session store.
