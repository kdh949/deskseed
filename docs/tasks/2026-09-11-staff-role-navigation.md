# 직원 권한별 진입 및 메뉴 복구

## 범위 / 사용자 시나리오
F08/F09/F10/F11/F21. REQ-AUTH-001, REQ-STAFF-001, REQ-AUDIT-001. 감사자는 감사, 관리자는 운영, 상담사는 티켓으로 진입한다. 허용된 내부 원래 경로를 보존한다. D-015/032, ADR 0004/0042 및 docs33 권한 행렬. 기존 getCurrentStaff/loginStaff/logoutStaff와 감사·관리자 read API 그대로 사용.

## 경계 / 재사용
STAFF_SESSION, STAFF_CONSOLE. AGENT_WORKSPACE/ADMIN_MANAGE capability와 역할을 공통 predicate로 사용. 감사 서버는 SECURITY_AUDITOR 전용이므로 ADMIN 메뉴에서도 제거. 감사자는 티켓 생성·검색 단축키·알림 API를 사용하지 않는다. 재사용: SeedPageShell/NavigationRail/TopBar/FeedbackState/Button, 기존 AdminShell. 권한 없는 로그아웃 실패는 재시도 안내하며 success로 표시하지 않는다.

## 상태 / 검증
알 수 없는 경로는 not-found. 거부 화면은 실제 로그아웃 후 다른 계정 로그인으로 이동. loading/error/anonymous 상태 유지. UI-002/004/006, AUTH-002 frontend unit/Storybook interaction+a11y/typecheck/lint/build/boundary 검사. 서버 세션 E2E/전체 backend gate 미실행. API schema, actor/audit event, transaction, idempotency, retry, privacy/retention, DB migration 변경 없음. PUBLIC/INTERNAL 경계 유지. UI revert 가능.

## 설명할 trade-off
메뉴와 라우트가 같은 서버 역할 정책을 따른다. UI는 보안 경계가 아니며 서버 권한 검사는 유지된다.
