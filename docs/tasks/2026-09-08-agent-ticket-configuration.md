# 상담사 티켓 필드·태그·상태 편집

관리자는 동일 폼의 고객/상담사 기본 사용을 설정하고 태그·상태를 관리한다. 상담사는 접수된 typed 값을 확인하고 서버 후보값 판정 후 기존 티켓 configuration 명령으로 저장한다. Saved View 필터 확장은 별도 슬라이스다.

REQ-CFG-010/011/012/013, CFG-001~006, CHG-001, CONC-001, ACC-001, UI-002/004, DOC-001; ADR 0041 유지. ADMIN 설정은 기존 CSRF/If-Match/admin audit, STAFF 읽기와 후보 판정은 BACKGROUND read authorization/required access audit, 저장은 기존 If-Match/clientCommandId와 한 TicketAudit를 따른다.

PUBLIC 고객 projection에는 태그/직원 필드/내부 상태를 새로 노출하지 않는다. 고객 최초 폼 binding은 보존하며 상담사 편집은 현재 발행된 기본 상담사 폼을 사용한다. DB/migration/외부 I/O/새 인프라는 추가하지 않는다. 서버가 숨긴 필드는 UI 입력 전송에서 제외하고 보관 값은 삭제하지 않는다. stale/실패 시 초안을 유지한다.

검증 계획: 후보 판정/readonly/필수값/권한/충돌/감사 회귀, OpenAPI parity/architecture, Storybook MCP 상태·키보드·a11y와 모바일, unit/typecheck/build/docs. 배포/부하 측정은 범위 밖이다.

## 완료 범위와 검증

- 상담사 context drawer에서 서버 판정 필드와 태그/업무 상태를 한 command로 저장한다. 새 기본 폼이 같은 버전 번호를 쓰더라도 formId가 다르면 저장을 거절한다. unknown 결과는 같은 version/body/clientCommandId로 재시도하며 conflict에서는 입력을 보존한다.
- 관리자 태그 생성/편집/활성화와 상태의 단계·표시 이름·사용 폼·기본 상태를 연결했다. 상태 category와 machine key는 기존 identity 규칙대로 편집하지 않는다. ambiguous 생성은 자동 재시도하지 않고 목록 확인을 요구한다.
- projection은 기존 command의 조건 평가를 재사용한다. 고객 intake binding을 변경하지 않고 현재 기본 상담사 폼을 사용하므로 동일 폼을 두 surface에서 쓰려면 관리자가 customer/agent 기본 옵션을 모두 선택한다.
- read/candidate는 BACKGROUND required access audit이며 semantic TICKET_VIEWED를 추가하지 않는다. 값/태그/상태는 기존 ticket transaction, CSRF, resource authorization, command replay와 audit rollback을 유지한다. 필드 조건 조회는 batch로 처리하며 새 외부 I/O와 migration은 없다.
- Passed: AgentTicketCommandIntegrationTest 21개의 기존 저장/권한/감사/상태 회귀와 신규 read/candidate/non-mutating/필수값/form identity 검증, ApiDocumentationIntegrationTest 5, ArchitectureTest 1. Staff unit 216, typecheck/build/boundaries/lint, focused Storybook MCP 18개와 전체 117/117 및 a11y. 1280/390/320px drawer 가로 넘침 없음과 모바일 육안 확인.
- Not run: 실제 백엔드 browser E2E, 운영 부하/EXPLAIN, 배포. Saved View의 태그/폼/커스텀 필터는 후속 PR이며 현재 requirement 전체 완료로 바꾸지 않는다.

## PR #164 보류 상태 보완

REQ-CFG-013, ADR 0041과 CFG-004, UI-002/004, DOC-001을 유지한다. 관리자 업무 상태 목록의 decoder와 생성 선택지가 서버 계약의 `ON_HOLD`를 사용한다. 보류 상태가 포함된 목록을 열고 새 보류 상태를 생성하는 흐름을 검증했다. 상태 category의 수정 불가 규칙과 ADMIN_UI CSRF/If-Match/권한/감사는 유지한다. 상담사 숫자 정밀도 보완은 이 변경의 범위가 아니다.

검증: `ON_HOLD` 포함 canonical 5개 category 목록과 비계약 `HOLD` 거절 unit 2개에서 실패→통과 확인. 관리자 태그·상태 Storybook MCP 9개 및 a11y, staff unit 220개, typecheck, 변경 파일 ESLint/Prettier, staff build와 디자인 시스템 경계 검사 통과. API/DB migration, 보관 정책, 외부 I/O 변경 없음. 실제 서버 연동 browser E2E, 부하 측정, 배포는 이번 보완에서 실행하지 않았다.
