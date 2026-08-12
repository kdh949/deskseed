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

## 4. Account authentication — accepted contract

Initial method: **email magic link only**.

- use Spring Security One-Time Token integration or an equivalent adapter with the same contract;
- production-style DB-backed token service in development and production;
- single use, default 15-minute TTL, configurable 5–60 minutes;
- enumeration-safe request response;
- delivery through durable outbound email and Mailpit in development;
- secure customer session cookie and explicit logout;
- token generation, consumption success/failure, replay, and account link are security-audited;
- passwords, social login, and external IdP are later capabilities.

The magic-link URL must not leak through application logs or third-party resources. The frontend consumes the token through a dedicated route and immediately establishes a server session.

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
- email verification provider.
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
- email enumeration 최소화.
- token logs/referrer leakage 방지.
- internal comment/child/audit leak 없음.
- XSS sanitization.
- magic link replay/expiry.
