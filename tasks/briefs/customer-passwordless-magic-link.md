# Customer passwordless magic link

## Goal

Passwordless 또는 기존 익명 identity만 enumeration-safe magic-link mail을 받고, purpose-bound proof를 한 번
소비해 onboarding 상태의 고객 세션을 만든다. Password credential 계정은 outstanding proof가 있어도 이
경로로 인증할 수 없다.

## Decision and source references

- Decisions: D-057, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirements: REQ-AUTH-001, REQ-AUTH-002, REQ-AUTH-004
- Plan/API: docs/56 Task 11, `requestCustomerMagicLink`, `consumeCustomerMagicLink`
- Gates: AUTH-001, AUTH-002, AUTH-003, AUTH-004, AUTH-008, ARCH-004, MAIL-001, MAIL-002, DOC-001

## Actor and source

- Identity가 확인되기 전 request/consume failure actor는 `SYSTEM`, 성공 consume actor는 검증된 `CUSTOMER`다.
- Source는 `CUSTOMER_PORTAL`이며 staff role, integration scope, 임의 actor header를 받지 않는다.
- Resource constraint는 normalized email의 active passwordless account 또는 이미 존재하는 customer identity와
  제출된 `PASSWORDLESS_LOGIN` proof다.
- Request/correlation context는 token, outbox, session과 required security audit에 함께 전달한다.

## Product and API contract

- `POST /api/v1/customer/auth/magic-link-requests`는 syntactically valid email에 identity 종류와 무관하게 같은
  `202`를 반환하지만 active passwordless account와 기존 anonymous identity에만 mail intent를 만든다.
- `POST /api/v1/customer/auth/magic-link-sessions`는 passwordless-only proof를 소비해 `200 CurrentCustomer`와
  rotated session cookie를 반환한다.
- 1~256자의 non-blank, control-free token은 proof format을 사전 판별하지 않고 lookup 뒤 generic `401`로
  처리한다. Blank, control character 포함, 256자 초과 token만 request validation `400` 경계다.
- Password account, disabled account, unknown/wrong-purpose/expired/replayed/concurrent-loser proof는 세션을 만들지
  않고 외부에 account state를 노출하지 않는다.
- 제한 초과는 `Retry-After`가 있는 generic `429`, limiter/DB/outbox/audit 실패는 generic `503`이다.
- 두 operation은 runtime parity를 갖춘 뒤 `FROZEN`으로 승격한다.

## In scope

- request/consume purpose-destination-network Redis limiter와 content-free fingerprint audit
- account-email advisory lock 아래 passwordless eligibility 확인
- digest-only single-use proof, passwordless account 생성 또는 verified identity 연결, current-session rotation
- generic response/problem, disabled/password-account 차단, replay/concurrency, limiter unavailable, audit/outbox rollback
- bounded passwordless onboarding projection과 protected request DTO string representation

## Out of scope

- Authenticated password/profile/registration-consent completion은 다음 child PR 범위다.
- Email equality 기반 anonymous ticket claim/list, explicit claim proof 변경
- Password reset, MFA, SSO, social login, frontend onboarding UI
- Supported-deployment limiter capacity 측정

## Invariants and failure semantics

- Limiter allowance는 identity/proof DB 조회와 transaction 전에 소비한다.
- Request는 normalized-email lock 아래 account row를 우선 확인해 password account가 같은 email의 customer row로
  우회되지 않게 한다.
- Existing verified identity만 재사용하며 unverified anonymous requester는 수정하거나 계정에 연결하지 않는다.
- Token consume, passwordless eligibility 재확인, account/session mutation, previous-session revocation과 required audit는
  하나의 transaction에서 commit/rollback한다.
- Password account용 outstanding proof는 실패 audit와 함께 소비해 반복적인 eligibility probing에 재사용되지 않게 한다.
- Required audit/outbox 실패는 generic `503`이며 token, account, session 상태를 모두 rollback한다.
- Retry는 새로운 allowance를 소비하며 request/session creation은 idempotent하지 않다. 외부 mail delivery는 commit 뒤다.

## Data and privacy

- DB에는 one-time token/session digest, account authentication method/version snapshot과 security audit만 저장한다.
- Raw email/token/session cookie, password/hash, company name은 limiter key, audit metadata, ordinary log에 없다.
- Limiter/audit에는 keyed destination/proof/network fingerprint와 generic reason만 남는다.
- 기존 credential/token/session/mail/audit retention을 재사용하며 export/webhook field를 추가하지 않는다.

## Threats changed

- Password-account magic-login bypass는 request와 consume의 locked eligibility 확인으로 차단한다.
- Enumeration과 brute force는 generic response, response padding, atomic shared limiter로 완화한다.
- Replay와 concurrent double session은 atomic proof consume으로 차단한다.
- Anonymous ticket takeover는 email equality가 unverified requester를 upgrade/claim하지 않도록 차단한다.
- Required audit bypass는 proof/session mutation 전체 rollback으로 막는다.

## Acceptance scenarios

- Given anonymous, passwordless, password, unknown identities, when requests are submitted, then all receive the same `202`
  while only anonymous/passwordless identities receive mail.
- Given a valid passwordless proof, when consumed, then one audited onboarding session is created and the current session rotates.
- Given a password account with an outstanding proof, when consumed, then generic `401` creates no session and consumes the proof.
- Given replay, expiry, wrong purpose, short but bounded malformed proof, or a concurrent loser, when consumed, then one
  invalid-proof problem is used; blank, control character, or oversized input remains a request-validation error.
- Given exhausted or unavailable limiter, when request/consume starts, then generic `429`/`503` occurs before identity/proof work.
- Given required audit or outbox persistence failure, when an otherwise-valid flow runs, then generic `503` rolls back token,
  mail, account, and session effects while the limiter allowance remains committed.

## Validation

- focused `CustomerMagicLinkAuthIntegrationTest`
- focused `CustomerAuthenticationLimiterUnavailableIntegrationTest`
- focused `ApiDocumentationIntegrationTest`
- `./gradlew --no-daemon fastTest contractTest integrationTest`
- `make docs-check` and bundle parity
- `git diff --check` and secret/output scans
- AUTH-006/ADR 0043 supported-deployment capacity target: `Not run`

## Compatibility and migration

- Uses V81 account/session/token/limiter columns without a new migration or backfill.
- Existing passwordless sessions remain schema-compatible; password accounts no longer receive or consume magic-login proof.
- Both magic-link operations become `FROZEN`; registration completion and `getCurrentCustomer` remain unfrozen.
- Rollback is application-only; committed proof consumption and session rotation intentionally remain single-use.

## Human explanation

- Account existence takes precedence over customer-directory existence so a password account cannot regain magic login through a
  second customer row with the same email.
- Existing verified identities can be linked safely, but historical anonymous requester ownership still requires its explicit proof.
- Required security audit shares the mutation transaction, while Redis allowance remains committed independently to prevent retries
  from bypassing throttling after a database failure.
