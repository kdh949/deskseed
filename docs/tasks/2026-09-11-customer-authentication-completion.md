# 비밀번호 우선 로그인과 passwordless 가입 완료

## Goal / Scope
F03/F04/F17: 일반 고객은 비밀번호로 로그인하고 passwordless 고객은 현재 프로필·비밀번호·동의를 완성한 뒤 원래 문의로 돌아간다. 화면/adapter/route guard/회귀 테스트/문서만 변경한다. reset, 익명 문의 claim, 서버 권한·DB·메일은 범위 밖.

## Decisions and contracts
REQ-AUTH-003/004, REQ-CONSENT-002; D-032/057/058, ADR 0042/0044, docs/56 §7. 기존 FROZEN createCustomerPasswordSession/consumeCustomerMagicLink/completePasswordlessCustomerRegistration/getCurrentCustomer/getCustomerCsrfToken/listCurrentCustomerConsentPolicies. HTTP schema 변경 없음.

## Actor, source, resources and audits
CUSTOMER / CUSTOMER_PORTAL, 현재 고객 세션과 CSRF. 이메일 값은 현재 계정에서 가져오고 completion request에 임의 email/customer ID를 넣지 않는다. 기존 CUSTOMER_REGISTRATION_COMPLETED/CUSTOMER_CONSENT_ACCEPTED 및 인증 security audit는 서버가 기록한다. 직원 권한과 티켓 소유권 변경 없음.

## Product and reuse plan
/customer/sign-in은 비밀번호 기본, 이메일 링크는 passwordless 보조 수단. /customer/register/complete는 서버 registrationState에 따라 접근한다. loading/error/anonymous/completed/required, 403/409/429/503 복구. Reuse: 고객 DsButton/Notification/ScreenState, 기존 가입 form. Compose: 목적별 form과 안전한 내부 복귀 경로. 추가 디자인 시스템 API 없음. 기존 토큰/레이아웃 유지.

## Invariants / failure semantics
server-side session이 authoritative. UI는 등록 미완료와 인증 실패를 구분하고 claim하지 않는다. Completion PUT은 CSRF를 먼저 읽고 한 번 제출하며 자동 재시도하지 않는다. 실패 시 입력 보존, 정책 충돌은 최신 약관 재확인. 성공 response로 세션을 갱신한다. 서버의 credential/session rotation 및 atomic consent/audit, durable mail 경계 유지.

## Data, privacy and threats
비밀번호는 component memory만 사용. 허용된 /account/requests 내부 복귀 경로만 sessionStorage에 잠시 보관하고 소비 후 삭제한다. 비밀번호/token/본문은 저장하지 않는다. 외부/관리자/쿼리 포함 복귀 경로는 거부. 회복 메시지는 계정 존재 여부를 드러내지 않는다. PUBLIC/INTERNAL projection, audit, retention/export/webhook 변경 없음.

## Acceptance / validation
- password 기본 탭, 잘못된 인증/제한/서버 오류 안내, 기존 문의 상세 복귀.
- PASSWORDLESS+REGISTRATION_REQUIRED 응답이면 가입 마무리로 이동. CSRF PUT 성공 후 원래 문의 표시; 임의 claim 호출 없음.
- 실패 시 password/profile 보존; anonymous/완료 계정은 적절한 화면으로 이동.
- 외부 URL과 관리자 경로로 복귀하지 않음.
UI-002/004/006, AUTH-006/008, CONSENT-002 관련 frontend 검사: test:customer, typecheck, lint, build:customer, check:design-system-boundaries, Storybook MCP interaction/a11y와 preview. 실제 DB/SMTP E2E 및 전체 AUTH/CONSENT backend gate 미실행. 수치는 PR에 기록.

## Compatibility / human explanation
DB migration/backfill 없음, 기존 frozen API와 PUBLIC-only 고객 경계 유지. UI revert 가능. 계정 상태는 서버 응답으로 판단하고, 인증 방식·가입 완료를 명시적으로 분리한다. 권한 확대나 더 복잡한 기반 기술은 필요하지 않다.
