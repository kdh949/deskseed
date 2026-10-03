# 상담사 대화 중심 디자인 개편

## Goal
상담사가 선택된 3번 시안처럼 정돈된 속성 패널과 넓은 대화 영역에서 답변하고 고객 정보를 필요할 때 연다.

## Decision and source references
- REQ-UI-001, REQ-UI-003, REQ-UI-005, REQ-UI-006. 상태 변경 없음.
- D-030, D-031, D-032; Accepted ADR 0020, 0021, 0044.
- docs 28~31, 40, 51; 선택된 Deskseed 합성 시안 3번.
- 기존 getAgentTicket 및 티켓 command/draft API 계약 유지. 신규 operation 없음.
- UI-001~006. frontend/에서 test:staff, build:staff, typecheck, check:design-system-boundaries, 관련 Playwright 및 Storybook MCP interaction/a11y.

## Actor and source
STAFF / AGENT_WORKSPACE. 기존 AGENT_WORKSPACE 및 READ/UPDATE capability와 티켓별 resource constraint를 그대로 사용한다. request/correlation/read-intent, access audit, mutation audit 경계는 변경하지 않는다.

## Product and UX contract
/agent/tickets/:ticketNumber의 기본 대화 공간 확대. 64px Deskseed 전역 rail, 좌측 속성, 중앙 대화/고정 composer, 기본 접힌 context drawer. 상태·우선순위·그룹·담당자는 펼쳐 두며 요청자 상세만 disclosure로 제공한다. Drawer Escape/포커스 복귀와 공개/내부 초안 분리 유지. loading/empty/error/denied/conflict 기존 상태 유지. 1280/1440/1920 폭 검증.

## Reuse plan
Reuse: SeedNavigationRail, SeedPageShell, SeedDrawer, SeedChoiceField, SeedComposer, SeedConversationTimeline/Item와 기존 Deskseed mark/icons.
Compose: 기존 실제 고객/관련 티켓/협업/외부 참조/최근 활동을 같은 context action에 연결.
Extend: SeedWorkspaceHeader.requester, SeedPropertyStack.details, SeedTicketWorkspaceShell.onContextOpen을 문서화된 선택적 API로 추가.
Add: 신규 독립 component 없음.

## In scope / Out of scope
상담 티켓 상세와 공유 전역 rail의 표현, Storybook 정상 시나리오 및 디자인 검증 문서. 백엔드, API, DB, 배포, 다중 티켓 탭, 새 컨텍스트 종류, 추가 전역 메뉴 구현 제외. 별도 탭이 없는 기존 context는 단일 접근 버튼을 사용한다.

## Invariants and failure semantics
문의 본문은 첫 PUBLIC comment; PUBLIC/INTERNAL server authorization/projection, 별도 draft, group active assignee, parent/child ownership 불변. 기존 단일 command transaction/audit 원자성, 실패 audit 처리, expected version/409, idempotency/retry와 external post-commit 경계를 변경하지 않는다. UI 배치 변경으로 command를 자동 실행하지 않는다.

## Data and privacy
기존 staff projection만 표시. 새 PII/secret 저장, retention, export/webhook 노출 없음. 합성 Storybook 데이터 사용.

## Acceptance scenarios
- Given READ/UPDATE 티켓, When 1280/1440/1920으로 열면, Then 상태/배정과 답변창이 보이고 기본 고객 패널은 접힌다.
- Given PUBLIC 초안, When INTERNAL 입력 후 PUBLIC으로 돌아오면, Then 두 초안이 섞이지 않는다.
- Given context 열기 버튼, When 열고 Escape로 닫으면, Then 원래 버튼으로 키보드 포커스가 복귀한다.
- Given 읽기 전용 또는 저장 충돌, Then 기존 권한/복구 및 초안 보존 동작을 유지한다.

## Compatibility and migration
OpenAPI/DB/backfill 변경 없음. 선택적 canonical props 추가이며 기존 호출은 호환된다. 되돌릴 때 이 작업의 UI 변경만 제거한다. 기존 작업 트리 수정은 보존한다.

## Human explanation
대화 영역 확보를 위해 중복 header 배정/SLA 표시와 열린 context 폭을 줄였다. 속성은 계속 노출하고 고객 정보는 한 번의 동작으로 연다. 성능 개선 수치나 실서버 배포 완료를 주장하지 않는다.

## Completion report

최신 main 기반 디자인 전용 작업 트리에서 Staff unit 206, Storybook MCP 61 interaction/a11y, typecheck/build, 전체 format:check/lint, boundary checks 통과. Playwright 기능/axe 9개는 `--ignore-snapshots`로 통과. 분리 전 자동 픽셀 비교 3개는 변경된 UI로 실패했고 baseline은 보존했다. 분리 후 자동 픽셀 비교는 재실행하지 않았으며 UI-005 baseline 검토·승인이 남아 있어 Draft PR로 제출한다. 1280/1440/1920 화면과 선택 시안 비교, 최종 1280px 속성 패널 스크롤 검증을 `design-qa.md` 최신 절에 기록했다. API/domain/audit/security/transaction/idempotency/privacy/migration 변경 없음. 배포·실서버 검증·Linux baseline·수동 스크린리더는 수행하지 않았다. 기존 chunk 크기 안내 외 성능 개선 측정 없음.
