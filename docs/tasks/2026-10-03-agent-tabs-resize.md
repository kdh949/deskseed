# 상담사 열린 티켓 전환과 패널 너비

## Goal

상담사가 여러 티켓 사이에서 초안을 보존하고 본인 화면에 맞는 속성·문맥 너비를 키보드와 포인터로 조절한다.

## Decision and source references

- P11 / AG-F05, AG-F09, AG-F12: 초안 보관 상태, 입력 중 Cmd/Ctrl+K, 열린 탭/미제출 표시/패널 너비. 선행 P10 #158, P09 #255.
- REQ-UI-001/003/005/006; D-030/031/032/033/047/062; Accepted ADR-0020/0021/0044/0045.
- docs28의 URL/탭/패널 범위, docs31 state ownership/초안/이동 경고, docs40 keyboard/visual, docs51 승인된 대화 중심 배치.
- API: 기존 getAgentTicket 및 draft/command operation만 사용. UI-001~006, DOC-001.

## Actor and source

STAFF/AGENT_WORKSPACE의 기존 session/READ/UPDATE. 명시적 티켓 이동은 현재 NAVIGATION interaction을 사용하고 탭 목록 복원은 detail prefetch나 semantic TICKET_VIEWED를 발생시키지 않는다. 403/404로 확인된 티켓은 목록에서 제거하며 오류 화면은 유지한다.

## Product and UX contract

- 열린 티켓은 staff별 sessionStorage에 티켓 번호만 기록한다. 제목/고객/본문/초안을 새 저장소에 복제하지 않는다. 클릭으로 기존 route를 이동하고 탭 닫기는 draft를 삭제하지 않는다. 현재 route의 미저장 경고를 취소하면 열린 티켓은 유지된다.
- 속성 240~420px(default 320), context 240~520px(default 320)를 staff별 localStorage preference로 기억한다. 대화 최소 480px와 #158의 기본 접힌 drawer를 유지한다. 저장소 실패/잘못된 값은 기본값으로 복구하고 메모리 상태에서 조작 가능하다.
- 크기 조절은 pointer drag, ArrowLeft/Right, Home/End로 가능하고 separator label/value를 노출한다.
- Cmd/Ctrl+K는 input/textarea/select/contenteditable 및 IME 조합 중이면 실행하지 않는다.
- 열린 탭의 `미제출` 표시는 기존 editor의 isUnsaved 여부만 메모리에 연결한다. 이동 시 남고 닫기에서 정리되며 실제로 다시 열면 editor 상태로 갱신한다. `추가 보관 없이 이동`은 기존 자동 보관 내용을 삭제하지 않으므로 삭제 완료로 표시하지 않는다. sessionStorage에는 번호만 저장한다. 새 세션 복원 시 실제로 열지 않은 티켓의 초안 존재를 추정하지 않는다. 서버 저장 성공을 의미하지 않는다.
- 초안 자동 저장 debounce(3초) 전에 전환하면 본문/서식이 사라지는 현상을 회귀로 재현했다. 사용자가 `초안 유지하고 이동`을 선택하면 두 채널의 body/rich document/version을 기존 IndexedDB에 commit한 뒤 이동한다. 보관 실패 시 현재 화면에 남으며 첨부 대기/실패 시 상태 확인을 요청한다. 댓글 전송이나 원격 저장으로 표시하지 않는다.
- loading/empty/error/denied/conflict는 기존 route 상태를 유지하고 탭 상태가 권한을 대신하지 않는다.
- 수동 초안 저장의 flush가 내부 실패를 처리하므로 무조건 성공하는 별도 메시지를 제거했다. 기존 draftSyncState와 채널별 실패 안내로 local-only/conflict/error를 구분하고 ticket command 전송과 혼동하지 않는다.

## Reuse plan

Reuse: canonical SeedPageShell/SeedTicketWorkspaceShell/SeedDrawer/SeedIconButton, 기존 editor drafts와 navigation blocker.
Compose: feature-owned staff별 목록과 preference, 기존 ticket route.
Extend: shell의 work navigation과 선택적 panel width/resize API, drawer의 선택적 width/resize API.
Add: canonical 열린 티켓 탐색 패턴과 bounded vertical resize handle. 범용 workspace registry/platform은 만들지 않는다.

## In scope / Out of scope

위 UI 수직 여정과 세 폭 회귀. 새 HTTP API/권한/계약, background permission polling, 티켓 내용 캐시, 복수 workspace 앱, drag reorder, 서버 preference, 고객 UI, P12 매크로/P13 AI/P14 presence는 제외한다.

## Invariants and failure semantics

PUBLIC/INTERNAL draft 분리, ownership/child/solve warning, current ticket source of truth, actor/source/access audit, version/idempotency/command transaction은 그대로다. 탭은 명시적 navigation 외 mutation/읽기를 하지 않고 권한 오류를 숨기지 않는다. 저장소는 best effort UI preference이며 실패해도 ticket command 성공/실패 판단에 개입하지 않는다.

## Data and privacy

새 preference 저장소에 기록하는 데이터는 staff ID로 구분한 ticket number 배열과 정수 너비 두 개뿐이다. 본문과 rich document는 기존 staff/ticket/channel별 IndexedDB·7일 retention을 사용한다. secret/PII/body/search query를 새 preference 저장소·로그·export에 복제하지 않는다.

## Acceptance scenarios

1. 티켓 2개를 순서대로 열고 탭 전환/새로고침 후 목록을 복원하며 PUBLIC/INTERNAL 초안이 기존 방식으로 유지된다.
2. 현재 티켓 닫기 중 미저장 이동을 취소하면 현재 탭이 남고, 닫기는 서버 티켓/초안을 삭제하지 않는다. 권한 거부는 탭 목록에서 제거한다.
3. 다른 staff 계정에는 이전 목록/너비가 섞이지 않고 malformed/차단된 storage에서도 화면이 동작한다.
4. 1280/1440/1920에서 separator를 키보드/포인터로 조절해 bounds/대화 최소 폭/focus/Axe를 확인한다.
5. 본문/제목/검색 입력 중 Cmd/Ctrl+K는 현재 입력과 route를 보존하고 비입력 영역에서는 검색으로 이동한다.

## Validation

MCP documentation → focused/full run-story-tests, changed/preview; staff unit/typecheck/lint/build/boundary; Playwright 3폭/keyboard/Axe/시각 검증; docs-check. Backend/실서버/수동 스크린리더/성능 측정은 실행하지 않는다.

## Compatibility and migration

HTTP/DB migration 없음. optional presentation API를 유지하고 frontend revert로 rollback한다. 번호와 너비 preference는 폐기 가능한 클라이언트 편의 상태다.

## Human explanation

현재 route와 draft 소유권을 그대로 사용해 화면 이동 편의를 추가한다. 탭 복원을 위해 서버 데이터를 미리 읽지 않으므로 권한은 실제로 열 때 다시 확인된다.

## Completion report

구현 완료: staff별 열린 티켓 번호/미제출 표시, bounded panel resize, 양 채널 local checkpoint 후 이동, 편집 영역 검색 단축키 보호. HTTP/DB/권한/actor/audit/idempotency/서버 retry/retention 변경 없음. 기존 상세 접근과 command 검증을 재사용하고 remote draft 성공을 추정하지 않는다. layout preference 외 새 영구 데이터는 없으며 성능 수치는 측정하지 않았다.

복구 후 검증: staff unit 45파일/273개, 전체 MCP 63파일/303개, macOS 전체 mock E2E 26개, 1280/1440/1920의 screenshot·Axe·resize keyboard/pointer·최소 대화 폭·focus·height 검증 통과. typecheck/lint/format/build/boundary도 통과했다. 이어서 `추가 보관 없이 이동` 문구와 수동 초안 저장의 무조건 성공 메시지 제거를 보완했고 해당 focused Storybook/E2E 및 최종 CI는 진행 중이다. Linux 3폭 실제 렌더는 보존된 자료로 검수했고 전체 Linux mock E2E는 원격 CI로 확인한다. 실서버/실제 backend/수동 screen reader/사용자의 최종 시각 승인은 실행하지 않았다. 구현 상태를 production 검증으로 상향하지 않는다.

MCP의 changed-stories는 154개를 반환했고 일부 feature/unit 파일을 graph에서 찾지 못해 get-stories-by-component로 소비자를 보강했다. 전체 suite로 누락을 확인한다. 프리뷰:

- http://localhost:6006/?statuses=affected;modified;new
- http://localhost:6006/?path=/story/04-patterns-seed-ticket-tabs--open-and-close
- http://localhost:6006/?path=/story/04-patterns-seed-workspace--resizable-workspace
- http://localhost:6006/?path=/story/03-components-seed-panel-resize-handle--keyboard-bounds
- http://localhost:6006/?path=/story/03-components-seed-surfaces--resizable-drawer
- http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--preserve-draft-before-navigation

복구 체크포인트: 기존 작업트리 소실 뒤 성공한 도구 patch 기록을 같은 P10 `bab8a7c9` 위에 재적용했다. 소실 전 staff unit 273개, 단독 macOS E2E 26개, Linux 3폭 렌더/Axe/keyboard, type/lint/build/boundary/docs/format은 통과했다. 전체 Storybook은 새 이동 story fixture를 보완한 뒤 자원 경합으로 완료되지 않았다. Linux baseline 3장은 임시 산출물에서 복구했고 Darwin 3장은 재생성해야 한다. 이 기록은 복구 후 코드 검증의 완료를 의미하지 않으며, 별도 후속 검증 결과로 대체한다.
