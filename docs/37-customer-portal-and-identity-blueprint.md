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

## 4. Account authentication

초기 권장:

- email magic link 또는 password + email verification.
- production에서 MFA는 admin/staff 우선.
- external IdP는 later.

## 5. Claim flow

```text
sign in/verify email
→ show claimable anonymous requests matching verified email
→ additional access token confirmation or secure email link
→ link CustomerAccount to Customer
→ revoke/retain old token according to policy
→ security audit
```

단순 email match 자동 claim 금지.

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
