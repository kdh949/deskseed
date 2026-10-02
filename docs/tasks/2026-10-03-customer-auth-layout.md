# P03 고객 인증 화면 읽기 순서와 로그인 방식 안내

## Goal / scenario

고객이 비밀번호 재설정 요청과 새 비밀번호 입력을 한 카드의 제목→안내→입력→제출→복귀 순서로 읽고, 로그인 방식에 맞는 안내와 복구 경로를 확인한다. UX 감사 C-F05와 C-F10의 로그인 모드 부분만 다룬다.

## Decision and source references

- D-032, D-057, D-061; Accepted ADR 0042/0044.
- REQ-AUTH-003/004; PRD 고객 인증 경계, docs 28~31/37/40/51/56, 기존 `2026-09-11-customer-password-reset.md`.
- `/customer/sign-in`, `/customer/password-reset`, `/customer/password/reset`.
- FROZEN `createCustomerPasswordSession`, `requestCustomerMagicLink`, `requestCustomerPasswordReset`, `resetCustomerPassword` 재사용. API 계약 변경 없음.
- UI-002/004/005/006, AUTH-007/008의 frontend 부분.

## Actor / boundaries

CUSTOMER_PORTAL의 익명 또는 인증 전 고객. API의 proof/session/CSRF/authorization, enumeration-safe response, passwordless eligibility와 서버 감사·transaction은 변경하지 않는다. 고객 화면은 staff/internal/audit 자료를 읽지 않는다.

## Reuse plan / scope

- Reuse: 고객 Storybook에서 계약을 확인한 DsButton, Notification, ScreenState.
- Compose: 기존 customer-auth-card 안에 현재 폼과 안내를 구성하고 reset 페이지에 한 열 레이아웃을 적용한다.
- Extend/Add: 없음. 신규 디자인 시스템 API·자산·의존성 없음.
- 로그인 설명과 비밀번호 복구 링크를 선택 방식에 맞춘다. 비밀번호 기본 방식이 먼저 표시된다.
- 비회원 번호 parser, 접수/가입/검색/관리 기능은 다른 slice다.

## Invariants / privacy / failure

자동 retry나 새 request는 추가하지 않는다. pending 중 중복 요청 방지, 실패 시 입력 보존, reset proof의 즉시 URL 제거·메모리 보관·성공/401 폐기, 계정 존재 비노출을 유지한다. password/token을 log·storage·audit에 추가하지 않는다. 서버 credential/session/security audit, durable mail intent 및 retention은 변화 없다. 인증/인가 우회, 재전송, SSRF, 새로운 외부 I/O 경계 없음.

## Acceptance

1. reset 요청/입력/성공/입력 오류 상태에서 제목·안내·폼·다음 행동이 하나의 카드 안에서 자연스럽게 이어진다. loading/denied는 기존 ScreenState 복구 경로를 유지한다.
2. 390×844, 768×1024, 1448×1086에서 가로 넘침 없이 입력·버튼·복귀 링크에 접근한다.
3. 비밀번호가 기본이며 이메일 링크로 전환하면 비밀번호 로그인 설명과 복구 링크가 남지 않는다. 되돌리면 복구 링크가 다시 표시된다.
4. 기존 400/401/429/503와 전송 성공, token scrub, 입력 보존 회귀가 유지된다.

## Validation / compatibility

Customer Storybook documentation/instructions 조회 후 focused run-story-tests, get-changed-stories/preview-stories, customer unit, typecheck, customer build, design-system boundaries, 관련 lint/format, 인증 경로의 mock browser viewport/keyboard 검증을 수행한다. 실제 SMTP/DB token 소비·세션 revoke와 배포 검증은 이 frontend slice에서 수행하지 않는다. Migration/backfill 없음; UI 커밋 revert로 복원 가능. 성능 수치 주장을 하지 않는다.

## Completion

기존 인증 의미를 유지하며 읽기 순서와 선택한 방식의 안내만 일치시켰다. requirement status는 변경하지 않는다.

- Passed: MCP `list-all-documentation`, `get-storybook-story-instructions`, foundations/reset/sign-in `get-documentation`; focused 및 전체 `run-story-tests` (`a11y: true`), `get-changed-stories`, 두 component의 `get-stories-by-component`, `preview-stories`.
- Passed: `npm run test:customer` (28 files, 120 tests), `npm run typecheck`, `npm run build:customer`, `npm run check:design-system-boundaries`, 변경 frontend 파일 ESLint/Prettier, `git diff --check`.
- Passed: mock Chromium `customer-auth-layout.spec.ts` (390×844, 768×1024, 1448×1086)와 기존 `customer-auth-continuation.spec.ts` (총 4 tests). 배치·가로 넘침·키보드·proof URL 제거·이메일 새 탭 재발급 후 원래 문의 이동을 확인했다. 테스트가 생성한 전체 페이지 PNG를 직접 확인했다.
- Red→green: 변경 전 ready/request-ready의 제목·설명 위치 검사와 이메일 링크 모드의 설명 회귀가 실패했고 수정 후 통과했다.
- Not run: 실제 SMTP 발송, backend DB의 일회용 proof 소비/세션 폐기, 배포 환경 검증, 다른 브라우저 엔진. API/schema/migration 및 의존성 변경 없음. 성능 벤치마크 없음.

Storybook previews (로컬 개발 서버 실행 중 접근 가능):

- http://localhost:6007/?path=/story/customer-portal-password-reset--ready
- http://localhost:6007/?path=/story/customer-portal-password-reset--request-ready
- http://localhost:6007/?path=/story/customer-portal-password-reset--complete
- http://localhost:6007/?path=/story/06-customer-customer-sign-in-page--password-login
- http://localhost:6007/?path=/story/06-customer-customer-sign-in-page--mode-switch
- 전체 변경 story: http://localhost:6007/?statuses=affected;modified;new
