# ADR 0042 — 고객 비밀번호 로그인과 매직 링크

## Status

Accepted — 2026-08-25

## Context

ADR 0029에서는 이메일 매직 링크를 고객 로그인 방식으로 정했습니다. 이번 변경에서는 신규 고객에게 비밀번호 회원가입·이메일 인증·로그인·재설정을 제공하고, 기존 passwordless 고객은 매직 링크로 로그인할 수 있게 합니다.

비밀번호 계정에도 매직 링크 로그인을 허용하면 비밀번호 인증을 건너뛸 수 있습니다. 다른 사람의 이메일로 가입을 미리 시작한 뒤 이메일 링크만으로 공격자가 정한 비밀번호를 활성화할 위험도 있습니다. 가입을 시작한 브라우저의 정보까지 함께 사용해 처리합니다.

고객 인증은 직원의 BCrypt/bootstrap 인증과 별도 bounded context로 관리합니다. 기존 익명 문의를 고객 계정에 연결할 때는 문의별 접근 토큰 또는 서명된 claim 증명을 요구합니다.

## Decision

- 신규 고객은 이메일 인증을 마친 뒤 비밀번호로 로그인합니다.
- 이메일 인증은 가입을 완료할 때 사용합니다. `Pending registration`을 활성화하려면 같은 `registration intent`의 이메일 토큰과 가입을 시작한 브라우저의 `continuation secret`이 모두 일치해야 합니다.
- 매직 링크는 비밀번호가 없는 `ACTIVE_PASSWORDLESS` 계정에만 발급합니다. 비밀번호 계정이 매직 링크를 요청해도 응답은 동일하게 `202`로 보내고 이메일은 발송하지 않습니다.
- passwordless 계정이 `ACTIVE_PASSWORD`로 바뀔 때는 가입 완료 화면에서 비밀번호·표시 이름·회사 이름·현재 동의 약관 버전을 제출해야 합니다. 기존 익명 문의는 문의별 claim 증명으로 연결합니다.
- 이메일 인증·매직 링크·비밀번호 재설정 토큰에는 목적, 해시 저장, 일회성 사용, 만료 시간을 적용합니다. 토큰을 발급한 용도와 실제 사용 목적이 다르면 거부합니다.
- 고객 비밀번호는 직원 인증과 별도의 adaptive one-way encoder로 저장합니다. Argon2id 초기 목표는 메모리 19 MiB, iteration 2, parallelism 1입니다. 배포 환경에서 처리시간을 측정한 뒤 work factor를 정합니다. 비밀번호 평문은 로그·감사 기록·응답·캐시·DB에 저장하지 않습니다.
- 비밀번호는 12~128자로 제한합니다. Unicode와 공백은 허용하고 제어 문자는 차단합니다. 대문자·숫자·특수문자를 섞도록 강제하는 조합 규칙은 사용하지 않습니다.
- 회원가입·비밀번호 재설정·매직 링크 요청은 계정 존재 여부가 드러나지 않도록 동일한 유형의 응답과 유사한 처리 비용을 사용합니다. 로그인 실패도 이메일 미등록, 잘못된 비밀번호, 비활성·passwordless 계정, 미완료 가입 모두 같은 invalid-credential 응답을 사용합니다.
- 인증 요청에는 정규화한 수신지와 요청자 네트워크 정보를 이용한 PostgreSQL 기반 throttling을 적용합니다. 제한을 넘기면 `429`와 `Retry-After`를 반환합니다.
- 로그인·토큰 사용·가입 완료 시 고객 세션을 새로 발급합니다. 비밀번호를 재설정하면 credential version을 올리고 기존 고객 세션을 모두 무효화합니다. 세션 쿠키는 HttpOnly, 운영 환경 Secure, SameSite=Lax로 설정하고 CSRF 보호를 적용합니다.
- 인증 정보·토큰·세션·동의 내역·감사 기록은 같은 트랜잭션에 저장합니다. 이메일 발송 요청은 DB에 먼저 저장한 뒤 전송합니다.
- 익명 문의를 계정에 연결할 때는 문의별 접근 토큰 또는 서명된 claim 증명을 요구합니다.

이번 ADR은 ADR 0029의 고객 인증 방식과 비밀번호 재설정 연기 결정을 변경합니다. ADR 0029에 정의한 일회용 토큰, 계정 존재 여부 보호, 세션 보안, 문의별 claim 증명, 이메일 발송 기록은 그대로 적용합니다.

## Browser completion actor consistency

가입 완료 화면은 표시 중인 customer ID를 `X-Deskseed-Expected-Customer-Id` 헤더로 전송합니다. 서버는 현재 로그인한 고객 ID와 헤더 값을 비교해, 서로 다르면 저장을 거부합니다. 실제 작업할 계정은 서버 세션에서 결정합니다.

이 헤더는 이전 클라이언트와의 호환을 위해 선택 항목으로 두고, 현재 Customer Portal에서는 항상 전송합니다. CSRF·rate limit·필수 감사 기록과 가입 충돌 `409` 처리는 기존 방식을 사용합니다. 세션 방식과 DB 구조도 그대로 유지합니다.

## Alternatives

- **모든 계정에 비밀번호와 매직 링크를 함께 허용:** 비밀번호 계정이 매직 링크로 로그인할 수 있어 제외했습니다.
- **이메일 토큰만으로 대기 중 가입 활성화:** 공격자가 다른 사람의 이메일로 가입 요청을 시작해 비밀번호를 등록할 수 있습니다. 브라우저별 `continuation secret`을 추가했습니다.
- **이메일 주소가 같으면 익명 문의 자동 연결:** 이메일 소유와 문의 접근 권한은 별도로 다룹니다. 문의별 접근 증명을 사용합니다.
- **직원용 BCrypt encoder 재사용:** 고객의 해시 설정·변경 작업을 직원용 bootstrap 인증에서 분리하기 위해 별도 encoder를 사용합니다.
- **외부 IdP/OIDC 우선 도입:** 초기 self-hosted 요구사항에 맞춰 고객 자체 인증을 먼저 구현하고, 외부 IdP 연동은 뒤로 미뤘습니다.

## Consequences

- `customerauth`가 비밀번호, 가입 요청, 용도별 토큰, 요청 제한, 세션과 보안 감사 기록을 관리합니다. 고객 프로필은 `customer`, 동의 정보는 별도 consent 모듈이 관리합니다.
- OpenAPI에 계정 상태와 오류 응답을 명시합니다. 예시에는 실제 사용 가능한 비밀번호·토큰·쿠키를 넣지 않습니다.
- Argon2id 처리시간은 지원 하드웨어에서 측정합니다. 없는 계정의 dummy hash 경로와 요청 제한도 함께 테스트해 응답시간 차이와 인증 요청 DoS를 다룹니다.
- 기존 passwordless 계정과 문의 claim 방식은 유지하고, 매직 링크 발급 대상은 비밀번호가 없는 계정으로 제한합니다.
- DB migration은 forward-only로 적용합니다. 직원 비밀번호 해시와 기존 문의 claim 증명은 변경하지 않습니다.

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
