# Ticket Workspace precision reconstruction — Design QA

## Evidence

- Source visual truth: `/Users/donghyunkim/Downloads/DeskSeed-새로운 화면/상담사/상담사_티켓 워크스페이스.png`
- Source pixels: 1448 × 1086.
- Browser-rendered implementation, responsive reference viewport: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-workspace-route-1448-final.jpg`
- Browser-rendered implementation, full context rail: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-workspace-route-1600-final.jpg`
- Implementation pixels/CSS viewport: 1448 × 1086 and 1600 × 1086 at 1:1 capture density.
- Full-view comparison: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-workspace-comparison-1448.jpg`
- Focused context comparison: `/Users/donghyunkim/.codex/visualizations/2026/08/28/01a04a08-e94b-7490-b877-0d7022de28b8/ticket-context-comparison.jpg`
- State: authenticated writable Deskseed agent ticket with four PUBLIC/INTERNAL comments, SLA, structured editor, macros, collaboration, related ticket, external reference, recent activity, and no pending error.

## Required fidelity surfaces

- Typography: compact 12–14 px control/body scale, 600-weight action labels, dense line height, tab hierarchy, and metadata contrast match the reference's visual rhythm. Deskseed's licensed/system font stack is retained rather than copying proprietary font assets.
- Spacing/layout: dark 168 px navigation, compact top and ticket headers, 288 px property column, flexible conversation column, 320 px context rail above 1500 px, 1 px dividers, low-radius controls, and bottom composer match the source composition. At 1448 px the context rail intentionally becomes the documented accessible drawer.
- Colors/tokens: dark teal frame, off-white workspace, neutral borders, mint positive badges, amber INTERNAL surface, blue active indicators, and compact elevation are all canonical tokens shared by the route and stories.
- Image/icon quality: no screenshot pixels, cropped reference assets, CSS drawings, emoji controls, or inline SVG are shipped. The approved Deskseed brand asset and canonical icon-library assets are used; avatars remain initials because the contract has no avatar URL.
- Copy/content: all labels are actual HTML and Korean product copy. Dynamic content follows the OpenAPI fixture rather than copying unsupported customer details from the reference.
- Responsive/accessibility: 1448 × 1086, 1600 × 1086, and the 200% equivalent 724 × 543 checks have zero page-level horizontal overflow. Drawer Escape restores focus, editor/toolbars are keyboard reachable, states are not color-only, and Storybook accessibility tests pass.

## Comparison history

- [P1 resolved] Properties previously used generic controls with different density. Canonical compact status/group/assignee choices and the calendar read-only field now match the reference's icon/value/clear/caret anatomy.
- [P1 resolved] The composer lacked the reference's editor and action hierarchy. The lazy rich-text editor now supplies block formatting, marks, lists, alignment, link, ticket-local attachment image, emoji, code, quote, macro review, attachment, explicit draft save, and split send action.
- [P1 resolved] Context content previously read as unrelated panels. Customer, related ticket, collaboration, presence, external reference, and recent activity now share one dense `SeedContextCard` grammar with matching count/status badges and header actions.
- [P2 resolved] Conflict actions and regular buttons were oversized. `SeedButton` compact uses the reference-like 28 px control, 12 px/600 label, 4 px radius, visible focus, and the conflict bar sits immediately above the composer.
- [P2 resolved] The rich editor initially inflated the main bundle. It now loads as a 399.07 kB/125.75 kB gzip lazy chunk, reducing the initial main chunk by 399.36 kB/125.63 kB gzip.
- Post-fix comparison found no actionable P0/P1/P2 mismatch within the committed contract. Contract-only omissions and the user-selected 1448 px drawer rule are intentional, documented differences.

## Interaction and runtime evidence

- Verified actual route at 1448 and wide desktop, context drawer open/Escape/focus return, editor lazy-load, and 200% equivalent layout.
- Verified 100-comment Storybook state: 100 all → 20 INTERNAL → 100 all, with zero horizontal overflow. Browser-control round trips were 320 ms and 305 ms and include automation transport, so they are not presented as pure render timings.
- Storybook MCP: all 60 stories passed with accessibility checks.
- Actual route console: zero warning/error entries in the final browser pass.

## Residual boundaries

- The reference-only phone/address/local-time/join-date fields, Cc/Bcc, remote image URLs, arbitrary HTML/style/iframe, and editable collaboration notes remain omitted because their contracts are absent or explicitly disallowed.
- The 805.35 kB main chunk still triggers Vite's existing 500 kB advisory even after rich-editor code splitting; this is a non-blocking follow-up optimization, not a visual fidelity defect.

final result: passed

# 2026-09-05 선택 시안 3 — 대화 중심 상담 화면

이번 작업의 최신 검증 결과다. 위 2026-08 기록은 과거 기준으로 보존한다.

## Evidence

- Source: `/Users/donghyunkim/.codex/generated_images/01a06ffa-26e5-7e51-b60b-08877c8d8dc8/exec-1a587810-b40d-4c96-9161-25668357a275.png`, 1586 × 992, 사용자가 선택한 Deskseed 합성 시안 3.
- Artifact root: `/Users/donghyunkim/.codex/visualizations/2026/09/05/01a06ffa-26e5-7e51-b60b-08877c8d8dc8/staff-focus-implementation/`.
- Actual captures: `workspace-1280.png` 1280 × 800, `workspace-1440.png` 1440 × 900, `workspace-1920.png` 1920 × 1080. CSS viewport와 픽셀 1:1, DPR 1. `context-1440.png`는 drawer 동작 검증 시점 캡처.
- `comparison.html` 및 `comparison.png`: 원본과 최종 1440 화면을 같은 열 너비로 정규화해 나란히 비교. 제목/요청자/속성 및 composer 확대 비교 포함. 원본은 약 1.101 source pixels per intended CSS pixel로 해석한다.
- State: AGENT/READ/UPDATE, #1042 결제 승인 오류, 고정 합성 고객과 네 PUBLIC/INTERNAL comment, PUBLIC 초안, context 기본 닫힘. 실제 AgentShellLayout와 AgentTicketWorkspacePage를 MSW 계약으로 렌더링.
- PR 분리 당시 캡처: [1440px 작업 공간](https://github.com/kdh949/deskseed/blob/1aad4984bd96c6d3b530483f0e0f126b3258f1cb/docs/assets/agent-conversation-focus/workspace-1440.png), [1280px 요청 정보 및 패널 스크롤](https://github.com/kdh949/deskseed/blob/1aad4984bd96c6d3b530483f0e0f126b3258f1cb/docs/assets/agent-conversation-focus/properties-1280.png). 당시 main 기반의 디자인 전용 작업 트리에서 촬영한 이력이며, 최신 검증은 아래 2026-10-03 기록을 따른다. 현재 저장소의 승인 자산 규칙을 유지하기 위해 원본은 고정 커밋에 보존한다.

## Findings

- P1 resolved: 넓은 전역 메뉴와 열린 context를 64px rail, 320px 속성, 중앙 대화, 48px context 접근 영역으로 정돈.
- P1 resolved: 작은 제목과 중복 header metadata를 제목 우선 계층과 요청자 행으로 변경. 상태/우선순위/배정은 왼쪽에 유지하고 요청 정보만 disclosure로 이동.
- P2 resolved: composer 바깥 카드 여백/테두리/그림자 제거, 하단 고정. 제목/작성자/avatar 크기를 시안의 계층에 맞춤.
- P2 resolved: 비교용 story의 합성 staff/ticket/channel IndexedDB 초안만 초기화하고 hydration 완료 뒤 입력과 모드 전환 검증.
- P2 resolved: 속성 패널의 min-height와 독립 스크롤을 보존했다. 1280 × 800에서 요청 정보를 펼친 뒤 644px 패널 안의 731px 내용을 87px 내려 하단 생성 시각까지 접근했다.
- Intentional differences: 현재 대화 필터, PUBLIC/INTERNAL 텍스트, 실제 SLA, 명시적 초안 저장과 서식 도구를 유지한다. 다중 티켓 탭/추가 전역 메뉴/개별 context 탭은 구현하지 않는다. 기존 canonical Deskseed mark/icons와 이니셜 avatar 재사용.
- 선택한 배치 범위에서 해결되지 않은 P0/P1/P2 시각 문제는 발견하지 않았다. 이는 기존 자동 픽셀 기준선 승인과 별개다.

## Verification

- Passed: 디자인 전용 작업 트리에서 Staff unit 31 files / 206 tests; Storybook MCP 전체 61 stories interaction/a11y. 분리 전 62개 실행에는 이번 PR에서 제외한 다른 작업의 story가 포함되어 있었다.
- Passed: typecheck, build:staff, 전체 format:check 및 lint, design-system boundary 4 tests + checker, git diff --check.
- Passed: Playwright 기능/axe 9 tests (`--ignore-snapshots`): ticket-workspace, frontend-system, agent-views-workspace. 공개/내부 초안, READ-only projection, 목록 필터/키보드, 설정 focus, background read intent 포함.
- Passed: CUA 1280/1440/1920 layout와 header context open → Escape → 원래 버튼 focus 복귀. 1280에서도 상태/배정과 send action 접근 가능.
- Pending: 분리 전 자동 픽셀 비교는 Queue 3개 폭에서 rail 폭/별도 heading 변경으로 5~7% diff를 보고했다. 이번 PR에는 별도 heading 변경을 제외했으나 rail 변경은 포함한다. 디자인 전용 작업 트리는 기능 검증에 `--ignore-snapshots`를 사용했고 자동 픽셀 비교를 다시 실행하지 않았다. Darwin/Linux baseline은 보존했으며 UI-005의 baseline 검토·승인은 남아 있다.
- Not run: Linux native pixel baselines, 실서버/API/DB, 수동 스크린리더, 배포. API/domain/audit/transaction/retention 변경 없음. 기존 Vite 500kB chunk 안내 유지, 성능 개선 수치는 측정하지 않음.
- Storybook 최초 timeout은 작업용 서버 재시작으로 해소. 중간 story 실패는 최종 전체 실행에서 해소.

REQ-UI-001/003/005/006; D-030/031/032; ADR 0020/0021/0044; UI-001~006. 세부 범위는 `docs/tasks/2026-09-05-agent-conversation-focused-redesign.md`.

final result: visual and interaction checks passed; automatic pixel baseline review pending

# 2026-10-03 P10 — 대화 중심 문맥 동선

기존 #158을 P09 #255 위에 적층해 최신 main의 글꼴/긴 제목 처리와 저장 정합성을 보존했다. 고객/협업/자료 탭으로 문맥을 구분하고 header 협업 작업에서 바로 협업 탭을 연다. 최신 활동은 현재 staff projection을 시간 역순으로 최대 4건 표시하며 반환된 목록을 펼칠 수 있다. Context는 한 번 mount하고 비활성 패널을 숨겨 입력과 focus 경계를 유지한다.

- 재사용: canonical SeedTabs/SeedButton/SeedContextCard/SeedDrawer, 기존 property/conversation/composer. 호환 확장: SeedTabs.items.panelId, SeedWorkspaceHeader.actions, SeedDrawer.keepMounted. 새 component/토큰/HTTP 계약은 없다.
- 실제 렌더: 합성 API의 Queue/Workspace 1280×800, 1440×900, 1920×1080, DPR 1, Chromium. 기존 넓은 메뉴→64px rail, 320px 속성, 접힌 context에 따른 의도한 레이아웃 차이를 확인했다. Darwin 기준선 6장은 이 렌더를 반영한다. 고객 화면 기준선은 변경하지 않는다.
- 검증: Storybook MCP 전체 298, staff unit 263, 개발용 Playwright 23, type/lint/build/boundary/docs 통과. 각 폭에서 고객→협업 방향키 전환, Escape, 원래 trigger focus, page Axe 0을 확인했다. 최신 6건 정렬/펼치기와 협업 초안의 탭 이동·닫기/재열기 보존은 story로 검증했다.
- Linux 실제 기준선 및 PR 최종 시각 검토는 pending이다. 기준선 갱신을 사람의 승인으로 기록하지 않는다. 기존 #158 참고 PNG는 최신 자산 경계를 지키기 위해 위 고정 커밋 링크에 보존했다.
- 추가 전체 E2E의 고객 로그인 모드/버튼 불일치는 담당자에게 전달했다(28 pass, 1 skip, 1 fail). 이 PR의 CI와 같은 개발용 E2E 23개는 통과했다. Backend/실서버/배포/수동 스크린리더/성능 수치는 검증하지 않았다. 기존 build chunk 크기 advisory는 남는다.

REQ-UI-001/003/004/005/006, REQ-TKT-012/014/015; UI-001~006, DOC-001. 상세 및 Storybook 링크는 [작업 기록](docs/tasks/2026-10-03-agent-context-ux.md)에 있다.
