# ADR 0042 — Password-primary customer authentication with passwordless magic-link onboarding

## Status

Accepted — 2026-08-25

## Context

ADR 0029은 고객 인증의 첫 방식을 email magic link로 정했다. 현재 제품 요구사항은 일반 고객에게
password registration, email verification, login, reset을 제공하면서도 기존 익명·passwordless 고객의
magic-link 접근을 유지해야 한다. 단순히 두 방식을 모두 허용하면 password 계정이 magic link로 비밀번호
검증을 우회하거나, 피해자 이메일로 미리 만든 registration intent가 이메일 링크만으로 활성화되는 경로가
생긴다.

고객 인증은 직원 BCrypt/bootstrap 인증과 독립적인 bounded context다. 기존 익명 문의의 소유권 역시
이메일 일치가 아니라 ticket-specific access token 또는 signed claim grant로만 이전해야 한다.

## Decision

- 고객 계정의 기본 인증 방식은 password-primary다. 신규 password 계정은 registration request,
  email verification, password login 순서로 활성화한다.
- email verification은 등록을 승인하는 증명이며 일반 로그인 수단이 아니다. Pending registration은
  email token과 같은 intent의 browser-bound continuation secret가 모두 일치할 때만 활성화한다.
- magic-link login은 password credential이 없는 `ACTIVE_PASSWORDLESS` identity에만 발급한다.
  Password 계정에는 externally identical `202`를 반환하되 login mail을 만들지 않는다.
- passwordless session은 명시적인 registration completion에서 password, display name, company name,
  현재 registration consent versions를 제출해야 `ACTIVE_PASSWORD`로 전환된다. 이 전환은 기존 익명
  티켓을 이메일로 claim하지 않는다.
- registration verification, magic login, password reset token은 purpose-bound, digest-only,
  single-use, expiring token이다. 다른 purpose의 consumer는 token을 거부한다.
- 고객 password는 직원용 encoder와 분리된 adaptive one-way encoder로 저장한다. 초기 목표는 Argon2id
  19 MiB, iteration 2, parallelism 1이며 지원 배포 환경에서 시간을 측정한 뒤 work factor를 동결한다.
  평문은 HTTP/application 경계 밖으로 나가지 않고 log, audit, response, cache, database에 남지 않는다.
- password request는 12–128 characters를 허용하고 Unicode와 space를 허용하며 control character를
  거부한다. Composition rule은 강제하지 않는다.
- registration, password reset request, magic-link request는 account existence나 credential state를
  드러내지 않는 generic response와 comparable work class를 사용한다. Password login은 unknown email,
  wrong password, disabled, passwordless, incomplete registration에 같은 invalid-credential problem을 쓴다.
- 모든 인증 request operation은 normalized destination과 requester network identity에 대한
  PostgreSQL-backed throttling을 적용하고 제한 시 `429`와 `Retry-After`를 반환한다.
- login/consume/completion은 현재 customer session을 rotate하고, password reset은 credential version을
  올린 뒤 모든 기존 customer session을 revoke한다. Cookie/CSRF 경계는 기존 HttpOnly,
  Secure-in-production, SameSite=Lax server session을 유지한다.
- credential, token, session, consent mutation과 required security audit는 같은 transaction에서
  commit/rollback한다. Email network I/O는 durable outbound intent commit 뒤에 실행한다.
- matching email alone은 anonymous ticket ownership, listing, claim을 절대 부여하지 않는다. ADR 0029의
  ticket-specific explicit claim proof 계약은 변경하지 않는다.

이 결정은 ADR 0029의 authentication-method와 password-reset deferral 부분만 supersede한다. ADR 0029의
single-use token, enumeration safety, session security, explicit claim, durable outbound-mail 경계는 유지한다.

## Alternatives

- 모든 계정에 magic link와 password를 함께 허용: password credential 우회 경로가 되고 계정 상태별
  보안 의미가 불명확해져 거부한다.
- email token만으로 pending registration 활성화: 다른 browser가 피해자 이메일로 선택한 password를
  설정할 수 있어 continuation proof를 함께 요구한다.
- verified email equality로 anonymous ticket 자동 claim: email ownership과 ticket ownership proof가
  다르므로 거부한다.
- 직원 BCrypt bean 재사용: 고객 credential의 parameter tuning과 migration을 직원 bootstrap 정책에서
  분리할 수 없어 거부한다.
- 외부 IdP/OIDC 우선 도입: 현재 self-hosted P0 요구사항보다 운영·secret·redirect 경계가 넓어 연기한다.

## Consequences

- `customerauth`는 password credential, registration intent, purpose-bound token, limiter, session,
  security audit를 소유한다. `customer`는 verified profile을 소유하고 consent는 별도 bounded module이
  소유한다.
- OpenAPI는 identity 상태와 generic failure를 명시하고 usable password/token/cookie 예시를 포함하지
  않아야 한다.
- Argon2id work factor는 지원 hardware에서 검증 시간을 측정하고, dummy hash path와 strict throttling으로
  unknown-account timing과 authentication DoS를 함께 검증해야 한다.
- 기존 passwordless account와 claim flow는 호환되지만 magic-link request의 eligibility가 좁아진다.
- 적용 migration은 forward-only이며 기존 staff password hash나 customer claim proof를 rewrite하지 않는다.

## References

- D-040, D-046, D-047, D-057
- ADR 0029, 0034, 0035
- REQ-AUTH-001, REQ-AUTH-002, REQ-AUTH-003, REQ-AUTH-004
- AUTH-001 through AUTH-008, MAIL-001, MAIL-002, ARCH-004
- `docs/37-customer-portal-and-identity-blueprint.md`
- `docs/56-customer-auth-consent-request-form-p0-implementation-plan.md`
- Spring Security password storage guidance: <https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>
- OWASP Password Storage Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>
- OWASP Authentication Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>
