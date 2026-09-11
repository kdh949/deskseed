# 비밀번호 우선 로그인과 passwordless 가입 완료

## Goal / Scope
F03/F04/F17: 일반 고객은 비밀번호로 로그인하고 passwordless 고객은 현재 프로필·비밀번호·동의를 완성한 뒤 원래 문의로 돌아간다. 화면/adapter/route guard와 가입 완료의 서버 expected-customer 검증, 회귀 테스트/문서를 변경한다. reset, 익명 문의 claim, 서버 권한·DB·메일은 범위 밖.

## Decisions and contracts
REQ-AUTH-003/004, REQ-CONSENT-002; D-032/057/058, ADR 0042/0044, docs/56 §7. 기존 FROZEN createCustomerPasswordSession/consumeCustomerMagicLink/completePasswordlessCustomerRegistration/getCurrentCustomer/getCustomerCsrfToken/listCurrentCustomerConsentPolicies. 가입 완료에 optional expected-customer header를 추가하며 기존 request body와 상태 코드는 유지한다.

## Actor, source, resources and audits
CUSTOMER / CUSTOMER_PORTAL, 현재 고객 세션과 CSRF. 이메일 값은 현재 계정에서 가져오고 completion request에 임의 email/customer ID를 넣지 않는다. 별도 expected-customer header는 인증 principal과 비교만 하며 변경 대상을 선택하지 않는다. 기존 CUSTOMER_REGISTRATION_COMPLETED/CUSTOMER_CONSENT_ACCEPTED 및 인증 security audit는 서버가 기록한다. 직원 권한과 티켓 소유권 변경 없음.

## Product and reuse plan
/customer/sign-in은 비밀번호 기본, 이메일 링크는 passwordless 보조 수단. /customer/register/complete는 서버 registrationState에 따라 접근한다. loading/error/anonymous/completed/required, 403/409/429/503 복구. Reuse: 고객 DsButton/Notification/ScreenState, 기존 가입 form. Compose: 목적별 form과 안전한 내부 복귀 경로. 추가 디자인 시스템 API 없음. 기존 토큰/레이아웃 유지.

## Invariants / failure semantics
server-side session이 authoritative. UI는 등록 미완료와 인증 실패를 구분하고 claim하지 않는다. Completion PUT은 CSRF를 먼저 읽고 한 번 제출하며 자동 재시도하지 않는다. 실패 시 입력 보존, 정책 충돌은 최신 약관 재확인. 성공 response로 세션을 갱신한다. 서버의 credential/session rotation 및 atomic consent/audit, durable mail 경계 유지.

## Data, privacy and threats
비밀번호는 component memory만 사용. 허용된 /account/requests 내부 복귀 경로만 15분 만료시간과 flow ID를 포함해 localStorage에 잠시 보관하고 소비 후 삭제한다. 동일 브라우저의 최근 로그인 요청 하나를 대상으로 하며, 재요청 링크는 유효한 복귀 경로를 명시적으로 전달한다. 비밀번호/token/본문은 저장하지 않는다. 외부/관리자/쿼리 포함 복귀 경로는 거부. 회복 메시지는 계정 존재 여부를 드러내지 않는다. PUBLIC/INTERNAL projection, audit, retention/export/webhook 변경 없음.

## Acceptance / validation
- password 기본 탭, 잘못된 인증/제한/서버 오류 안내, 기존 문의 상세 복귀.
- PASSWORDLESS+REGISTRATION_REQUIRED 응답이면 가입 마무리로 이동. CSRF PUT 성공 후 원래 문의 표시; 임의 claim 호출 없음.
- 실패 시 password/profile 보존; anonymous/완료 계정은 적절한 화면으로 이동.
- 외부 URL과 관리자 경로로 복귀하지 않음.
UI-002/004/006, AUTH-006/008, CONSENT-002 관련 frontend 검사: test:customer, typecheck, lint, build:customer, check:design-system-boundaries, Storybook MCP interaction/a11y와 preview. 실제 DB/SMTP E2E 및 전체 AUTH/CONSENT backend gate 미실행. 수치는 PR에 기록.

## Compatibility / human explanation
DB migration/backfill 없음, 기존 frozen API와 PUBLIC-only 고객 경계 유지. UI revert 가능. 계정 상태는 서버 응답으로 판단하고, 인증 방식·가입 완료를 명시적으로 분리한다. 권한 확대나 더 복잡한 기반 기술은 필요하지 않다.

## 리뷰 수정과 검증 경계
- A 폼에서 B 쿠키/CSRF로 제출해도 기대 고객 불일치를 저장 전에 409로 거부한다. 거부 감사 실패도 성공으로 처리하지 않는다. 기존 credential/profile/consent/session 원자성과 익명 티켓 비자동 claim을 유지한다.
- CSRF GET 또는 completion PUT의 401은 세션을 재확인하고 원래 목적지를 포함해 로그인으로 복구한다. 409에서 고객이 바뀌면 이전 폼을 폐기하고, 같은 고객의 정책 변경은 입력을 유지한다.
- 새 이메일 탭 → 만료 링크 → 재요청 → 새 탭 성공 → 원래 문의 복귀를 Playwright의 별도 page로 검증한다. 통신은 mock이며 실제 메일/DB를 잇는 브라우저 E2E와 구분한다.
- AUTH-003/008: PostgreSQL/Redis 기반 CustomerPasswordlessRegistrationCompletionIntegrationTest에서 불일치·CSRF 교체·기존 무헤더 호환과 credential/consent/audit 무변경 검증. UI-002/004/006: customer unit, MCP interaction/a11y, typecheck/lint/build/boundary.
- migration/backfill 없음. 서버를 먼저 배포하면 이전 UI와 호환되며 새 UI의 header를 검사한다. 이전 서버는 새 header를 무시하므로 서버 배포 전에는 교차 탭 보호가 보장되지 않는다.
