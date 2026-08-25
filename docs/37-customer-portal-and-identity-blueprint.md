# Customer Portal and Identity Blueprint

## 1. 단계적 모델

### Stage A — Anonymous allowed

- name/email/request form.
- opaque access token or one-time link.
- request detail public projection.
- token hash only in DB.

### Stage B — Registration optional

- verified customer account.
- existing anonymous ticket claim after email verification.
- all own requests list.
- public comments and follow-up.

### Stage C — Registration required

- unauthenticated form redirects to sign-in/register.
- admin can still create requester/ticket.
- organization/shared requests later.

## 2. Customer and account separation

```text
Customer = business identity/profile
CustomerAccount = authentication identity
```

같은 이메일 입력만으로 과거 티켓을 연결하지 않는다. 이메일 소유권 검증이 필요하다.

## 3. Anonymous access token

- cryptographically random.
- token plaintext one-time display.
- DB hash + created/last used/expires/revoked.
- rotate/revoke.
- rate limit.
- wrong ticket/token pair는 동일한 404.

### 3.1 Public request abuse boundary

`POST /api/v1/requests`는 customer/ticket을 만들기 전에 Portal-owned PostgreSQL fixed-window limiter를 통과한다. 대상 이메일,
신뢰된 client 주소, 전체 요청은 서로 다른 bucket으로 계산하지만 row에는 각각의 purpose-bound HMAC fingerprint만 남긴다. raw
email, IP, forwarding header, token, ticket body는 저장하거나 audit/log metadata로 복사하지 않는다.

직접 연결 peer가 설정된 `trusted-proxy-cidrs` 안에 있을 때만 `X-Forwarded-For` chain을 해석한다. 그 밖의 peer가 보낸 header는
identity를 바꾸지 못한다. 신뢰 프록시에서 온 malformed, 복수, 또는 hop 상한 초과 chain은 400으로 fail closed한다. 제한은 `429`와
`Retry-After`를 반환하고, limiter persistence 실패는 customer/ticket을 만들지 않는 503이다. limiter budget은 별도 transaction으로
먼저 commit하므로, 이후 ticket/audit/outbox 실패가 budget을 되돌려 재시도 남용 경로가 되지 않는다.

## 4. Account authentication — accepted contract

ADR 0042의 목표 방식은 **password-primary with passwordless-only magic-link login**이다.

```text
NO_ACCOUNT
  → registration intent
  → email token + same-browser continuation proof
  → ACTIVE_PASSWORD

ANONYMOUS_REQUESTER or ACTIVE_PASSWORDLESS
  → PASSWORDLESS_LOGIN magic link
  → passwordless session / REGISTRATION_REQUIRED
  → password + profile + current registration consents
  → ACTIVE_PASSWORD

ACTIVE_PASSWORD
  → password login
  → purpose-bound password reset
```

### 4.1 Password registration and login

- registration request stores a customer-specific adaptive password hash, never plaintext;
- email verification activates a pending registration only when a single-use email token and the
  same intent's browser-bound continuation proof both match;
- login always performs real-or-dummy password-hash work and uses one generic invalid-credential
  response for unknown, wrong-password, disabled, passwordless, and incomplete states;
- normalized destination and requester network identity have PostgreSQL-backed rate limits;
- successful login rotates the server-side customer session and appends a metadata-only security
  event before returning success;
- password reset is enumeration-safe, purpose-bound, single-use, expiring, and revokes all existing
  customer sessions by advancing the credential version;
- password input is 12–128 characters, permits Unicode and spaces, rejects controls, and has no
  forced composition rule;
- social login, external IdP, SSO, and MFA remain later capabilities.

The initial customer encoder target is Argon2id with 19 MiB memory, two iterations, parallelism one,
16-byte salt, and 32-byte hash. It is distinct from staff BCrypt/bootstrap configuration and must be
tuned with supported-deployment timing evidence before implementation is called release-ready.

### 4.2 Passwordless magic-link boundary

- production-style DB-backed, purpose-bound token service is used in development and production;
- `PASSWORDLESS_LOGIN` is single-use with default 15-minute TTL, configurable 5–60 minutes;
- request response is enumeration-safe and sends mail only for an identity without a password;
- consume creates/rotates a customer session whose projection requires explicit registration
  completion before password-account capabilities are available;
- completion requires session+CSRF and atomically writes password, display name, company name,
  current registration-policy acceptances, credential version, and rotated session state;
- token generation, consumption success/failure, replay, and completion are security-audited;
- delivery uses durable outbound email and Mailpit in development.

Magic-link, verification, and reset URLs must not leak through application logs, audit metadata,
referrers, or third-party resources. Browser token routes remove the token before follow-up requests.

### 4.3 Session and identity boundary

- customer cookies remain HttpOnly, Secure in production, SameSite=Lax, and server-revocable;
- password reset revokes every old customer session; login and completion rotate the current one;
- `CurrentCustomer` exposes bounded credential/registration/authentication-method states, not hashes,
  token state, limiter data, or security-audit metadata;
- company name is profile PII and is not copied to routine authentication events or ticket-list
  projections unless a contract explicitly needs it;
- required credential/security-audit persistence failure is fail-closed.

## 5. Claim flow

```text
sign in/verify email
→ do not auto-list or auto-claim solely from matching email
→ require existing request access token or a signed per-request claim link
→ link CustomerAccount to Customer
→ revoke/retain old token according to policy
→ security audit
```

단순 email match 자동 claim 금지.

Implemented Stack F contract:

- an existing request-access token can be presented directly, or exchanged for a 15-minute
  HMAC-signed ticket/email-bound grant whose database row stores only digest/fingerprint state;
- the authenticated account, anonymous requester and proof email must all normalize to the same
  address, but the proof—not the address—authorizes the transition;
- success changes the current Ticket requester, revokes legacy request tokens, consumes the grant
  when applicable, and commits ticket/security audit in the same transaction;
- tamper, expiry and replay are generic not-found; a valid proof for a different verified account
  is denied without consuming it.

## 6. Portal capabilities

MVP:

- create.
- view one request.

Account release:

- list/filter/search own requests.
- add public comment.
- view open/solved.
- follow-up/reopen policy.
- notification preferences later.

Implemented projection boundary: list and detail begin with the authenticated
`customer_id` resource constraint and `CUSTOMER_REQUEST` kind. Responses contain ticket number,
subject, customer status/timestamps and PUBLIC comments only. INTERNAL comments, child relations,
staff/group/assignee fields, audit identifiers/events and mail delivery metadata are excluded
server-side.

PUBLIC follow-up uses a stable client command ID. Exact transport replay returns the original
comment and does not duplicate its ticket audit or acknowledgement mail intent. PENDING reopens to
OPEN; SOLVED/CLOSED require a later explicit reopen policy and currently return conflict.

## 7. Customer status projection

내부 상태를 고객 친화 label로 변환할 수 있다.

```text
NEW/OPEN/HOLD → 처리 중
PENDING → 고객 답변 대기
SOLVED/CLOSED → 해결됨
```

내부 group/assignee/status 세부를 무조건 공개하지 않는다.

## 8. Admin settings

- access mode.
- anonymous token expiry.
- allow customer follow-up.
- solved reopen window.
- display public status mapping.
- customer authentication method and purpose-specific TTLs.
- password length bounds and rate-limit policy.
- email verification/delivery provider.
- brand/logo/colors.

The implemented access-mode slice exposes only the typed three-mode setting. ADMIN writes carry
an expected version and commit `CUSTOMER_ACCESS_MODE_CHANGED` in the admin/security ledger with the
setting mutation. Registration-required blocks anonymous creation but intentionally preserves
existing request-access-token detail capability.

## 9. Notification later

- request received.
- public agent reply.
- solved.
- verification/magic link.

notification event와 actual email delivery를 분리하고 delivery audit/observability를 둔다.

## 10. Security acceptance

- 다른 고객 ticket 접근 불가.
- registration, login, reset, magic request의 email/credential-state enumeration 방지.
- token logs/referrer leakage 방지.
- internal comment/child/audit leak 없음.
- XSS sanitization.
- verification/reset/magic token purpose·replay·expiry·concurrent consume 검증.
- registration continuation-proof mismatch는 account를 활성화하지 않음.
- password reset 뒤 모든 old session 거부.
- passwordless completion은 email equality로 ticket을 claim하지 않음.
