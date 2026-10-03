# P12 매크로 생성·편집 동선 개선

## Goal / actor
상담사(STAFF/AGENT_UI)는 티켓의 빈 매크로 메뉴에서 개인 매크로 관리로 이동하고, 관리자(STAFF/ADMIN_UI)는 공유 매크로를 같은 편집 흐름으로 관리한다. 편집 시작 시 이름에 집중하고 저장과 활성화를 구분한다. 감사 AG-F13을 해결한다.

## References
- REQ-CFG-003, REQ-UI-002, REQ-UI-004, REQ-UI-005; D-007, D-030, D-032, D-033, D-035; Accepted ADR-0024, ADR-0040. 결정 변경 없음.
- docs/47 §4, docs/51; UI-001/002/004/005/006, TKT-006, CHG-001 검증 경계.
- 기존 listAccessibleMacros/listPersonalMacros/createPersonalMacro/createPersonalMacroVersion/activatePersonalMacroVersion/deactivatePersonalMacro/getPersonalMacroHistory 및 동일 shared operation을 재사용. HTTP 계약 변경 없음.

## Product and scope
- /agent/personal-macros 경로 표시 수정. 개인/공유 편집 중 목록과 빈 안내를 숨기고 이름에 초점. 저장/닫기는 폼 상단 sticky 동작으로 유지.
- 카드의 반복 이름을 화면에서 줄이고 보조기술의 구별 가능한 작업 이름은 유지.
- 로컬 close/route 이탈/새로고침에서 미저장 변경 보호. 저장 중에는 버리기 금지. 새 편집 프레임워크나 서버 초안 API 없음.
- SeedMacroMenu의 optional onManage 콜백과 기존 onRetry를 idle/empty 복구 동작으로 문서화. 관리 이동은 host router가 담당하며 티켓의 기존 draft guard를 통과한다. Escape는 trigger로 초점을 복원한다.
- loading/empty/error/denied와 409/412/불확실한 저장 결과의 기존 보존·재조회·충돌선택 경로 유지.

## Invariants, data and failure semantics
- PERSONAL 소유자, SHARED 관리자 및 기존 macro:shared:manage 권한/CSRF/expected actor/If-Match 검사는 서버 소유. 클라이언트에서 권한을 확장하지 않는다.
- 저장은 새 버전만 생성하고 활성 버전을 바꾸지 않는다. 티켓 적용은 기존 preview 후 normal command 한 번. PUBLIC/INTERNAL, group/assignee invariant, audit 원자성 불변.
- 기존 MACRO_CREATED/VERSION_CREATED/ACTIVATED/DEACTIVATED 이벤트와 request/correlation, transaction/rollback/strict audit 경계 유지. 새 이벤트/외부 I/O/재시도/캐시 없음.
- 입력은 component memory에만 있고 새로운 persistent 저장·로그·retention/PII/export/webhook 변경 없음. 새 SSRF/XSS/impersonation surface 없음.

## Acceptance
- Given 빈 목록, When 만들기, Then 이름 focus·빈 안내 제거·저장/닫기 가시성. 1280/1440/1920px overflow 없음.
- Given 편집값, When 닫기/route 이탈, Then 취소 시 그대로 복구; 명시적 버리기 후 목록/다음 route. 새로고침은 beforeunload 경고.
- Given 412, When 재조회 후 선택, Then 기존 action과 로컬 문구를 보존하고 최신 If-Match로 저장.
- Given 빈 티켓 라이브러리, When 새로고침/관리, Then 기존 읽기/host route만 호출. denied/loading에서 실행 버튼 미노출.

## Compatibility and trade-off
API/schema/migration/backfill 없음. UI 커밋 되돌리기로 rollback. 기존 폼을 재배치하고 기존 Drawer를 조합해 별도 editor/layout framework를 피했다. 목록 표시가 바뀌어도 버전/활성화 의미는 유지된다. 전송·매크로 backend, 운영 데이터, 배포, 성능 부하 측정은 이 PR 범위 밖이다. REQ 구현 상태는 기존과 동일하다.

## Validation
실행 결과와 Storybook preview는 완료 시 기록한다. Frontend unit/type/build/lint/format/boundary, focused 및 full Storybook MCP/a11y, Chromium 업무 폭 검증, docs-check를 수행한다. backend 코드가 없어 기존 macro transaction gate의 서버 테스트를 재실행하지 않는다.

## Reuse plan and completion
- Reuse: SeedButton/TextField/SelectField/TextAreaField/Notice/FeedbackState/Drawer 및 기존 token.
- Compose: 기존 폼 상단 저장바와 local dirty guard. Extend: SeedMacroMenu optional onManage와 idle/empty refresh; 공개 Storybook 설명 및 recovery story 추가. Add: 없음.
- Passed: staff unit 43 files/255 tests; full Storybook MCP 292 + a11y, 최종 sticky CSS 후 focused 4 + a11y; typecheck/lint/build:staff/DS boundary/format/diff/docs-check.
- Passed: mock Chromium E2E 3(1280/1440/1920), overflow/Axe 0, 긴 preview 스크롤에서 elementFromPoint로 저장 버튼 겹침 없음; 닫기/route 취소 및 버리기, 실제 beforeunload dismiss, 저장 뒤 별도 활성화 CTA 검증. 1280px 실제 캡처 육안 검수.
- 초기 E2E fixture의 잘못된 capability/탐색 role을 실제 계약에 맞게 교정했다. 긴 preview에서 상단 shell에 버튼이 가려지는 실패를 재현한 뒤 기존 frame token을 적용해 재통과했다.
- Not run: 운영/DB/API/SMTP 연결 E2E, Firefox/WebKit, 별도 pixel baseline 및 성능 부하 측정, 배포. API/권한/감사 서버 변경 없음.
- 증거: 로컬 감사 output의 implementation-evidence/p12. UI 기준 스냅샷 자동 갱신 없음.

### Storybook previews
- [07 Screens/Macro Management / Create And Review](http://localhost:6012/?path=/story/07-screens-macro-management--create-and-review)
- [07 Screens/Macro Management / Empty Editor And Discard](http://localhost:6012/?path=/story/07-screens-macro-management--empty-editor-and-discard)
- [07 Screens/Macro Management / Shared Activation History](http://localhost:6012/?path=/story/07-screens-macro-management--shared-activation-history)
- [03 Components/Seed Workspace Controls / Empty Macro Recovery](http://localhost:6012/?path=/story/03-components-seed-workspace-controls--empty-macro-recovery)
- [06 Domain & Workspace/AgentTicketEditorWorkspace / Macro Preview Review](http://localhost:6012/?path=/story/06-domain-workspace-agentticketeditorworkspace--macro-preview-review)
- [전체 변경 스토리](http://localhost:6012/?statuses=affected;modified;new)
