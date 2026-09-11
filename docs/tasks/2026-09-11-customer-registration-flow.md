# 고객 가입 요청과 이메일 인증 완료

## Goal
고객이 현재 가입 약관을 읽고 개별 동의한 뒤 같은 브라우저에서 이메일을 인증하고 로그인으로 이동한다. 프론트엔드 감사 F01/F05/F06 수정.

## Decision and source references
D-032, D-057, D-058; Accepted ADR 0042/0044; docs/56 §7.1/7.2와 docs/55. REQ-AUTH-003, REQ-CONSENT-002. 기존 FROZEN requestCustomerRegistration, verifyCustomerRegistration, listCurrentCustomerConsentPolicies를 그대로 소비하며 HTTP schema 변경 없음.

## Actor and source
CUSTOMER / CUSTOMER_PORTAL. 익명 가입과 continuation cookie 기반 인증. 직원 API/scopes 없음. 고객 토큰은 fragment에서 먼저 제거하고 memory에서 한 번만 소비한다. Request/correlation/security audit 생성은 기존 서버가 소유한다.

## Product and UX contract
/customer/register → /customer/sign-in/check-email (registration 목적) → /customer/register/verify → /customer/sign-in. loading/empty/error, invalid proof, conflict, unavailable, success. 키보드로 약관 details와 개별 checkbox에 접근. 동의는 policyKey+version, 필수 정책만 필수 표기. 공개된 정책이 없거나 응답이 잘못되면 가입을 제공하지 않는다.

## Reuse plan
Reuse: 고객 DsButton, ScreenState, Notification, CustomerIcon. Compose: 기존 가입 form과 native details/checkbox/link. Extend/Add: 없음. 기존 고객 색상·간격·레이아웃 유지.

## In scope / Out of scope
가입 요청부터 인증 완료까지 UI, API adapter, 실패 테스트, story, 문서. 비밀번호 재설정과 passwordless completion은 다음 독립 슬라이스. 서버/DB/메일 worker/동의 법적 내용 변경 없음.

## Invariants and failure semantics
verification은 로그인이 아니며 익명 티켓 소유권을 부여하지 않는다. continuation cookie 없이 우회하지 않는다. 서버의 계정/동의/security audit 원자성 유지. UI는 일회성 proof를 자동 재시도하지 않으며 성공 불명확 시 로그인 또는 재등록으로 회복. 정책 버전 충돌 시 수락 선택을 비우고 최신 정책을 확인한다. 메일은 기존 durable post-commit intent.

## Data and privacy
email/profile/password는 입력 중 component memory만 사용. password/token을 저장소/로그/URL query/history state에 저장하지 않는다. 이메일 안내 history state에는 email과 목적만 포함. 공개 정책 본문은 React plain text로 렌더링하며 HTML과 외부 fetch 없음. retention/export/webhook 변경 없음.

## Threats changed
잘못된 재전송 API, 선택 동의 강제, invalid policy 누락, proof URL 노출 및 중복 소비를 방지. 권한·audit 정책 완화, cross-app import 없음.

## Acceptance scenarios
- Given 필수/선택 정책, When 필수만 동의해 가입, Then 선택 정책은 제출하지 않고 가입 메일 안내를 표시.
- Given 가입 이메일, When 같은 브라우저에서 verification, Then fragment 제거 후 204 성공을 표시하고 비밀번호 로그인으로 안내.
- Given invalid/expired proof, 409 정책 충돌, 503, Then 성공을 합성하지 않고 재등록/로그인 복구 경로 제공.
- Given 정책 없음/잘못된 본문, Then 약관을 합성하거나 빠뜨린 채 제출하지 않음.

## Validation
AUTH-005, CONSENT-002, UI-002/004/006 관련 프론트엔드 회귀. npm run test:customer, npm run typecheck, npm run lint, npm run build:customer, npm run check:design-system-boundaries; MCP run-story-tests, get-changed-stories, preview-stories. 실제 DB/메일 E2E 및 backend gate는 이 UI 변경에서 미실행이며 전체 AUTH/CONSENT gate PASS를 주장하지 않는다.

## Compatibility and migration
기존 FROZEN API와 쿠키/CSRF/메일 경계 유지. DB migration/backfill 없음. UI revert로 롤백 가능. 고객 API 계약 변경 없음.

## Human explanation / Completion
가입 검증과 로그인을 분리해 목적이 다른 proof를 혼용하지 않는다. 서버가 현재 동의 버전과 인증 여부를 판단하고 UI는 상태와 복구만 표현한다. 검증 수치와 Storybook 링크는 PR에 기록한다.

## PR 리뷰 보완
400/409 뒤 현재 정책을 재조회하고 key/version/required 집합이 바뀐 경우에만 기존 동의를 초기화한다. 일반 입력 오류에서는 선택과 입력을 보존한다. email 254, password 12~128, displayName 1~100, companyName 1~160의 서버 코드 포인트 검증을 적용하고 필드에 오류를 연결한다. 약관은 신규 고객 DS `ConsentDocument`의 검증된 typed block으로 제목·목록 순서·HTTPS 링크 의미를 보존한다. 기존 버튼/알림/레이아웃을 재사용하며 편집기나 외부 문서 fetch를 추가하지 않는다.
