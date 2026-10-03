# 상담사 대화 중심 문맥과 최근 활동

## Goal

상담사가 대화를 유지하면서 고객·협업·자료의 필요한 문맥과 최신 활동에 바로 접근한다.

## Decision and source references

- P10 / AG-F06의 정보 위계, AG-F07, AG-F12 문맥. 기존 PR #158을 P09 #255 위에서 보완한다.
- REQ-UI-001/003/004/005/006, REQ-TKT-012/014/015; D-008/030/031/032/033/047/062; Accepted ADR-0020/0021/0044/0045.
- docs28~31/40/51/55 및 기존 #158의 Deskseed 합성 시안. 최신 main의 제목 줄바꿈과 글꼴 가독성을 유지한다.
- 기존 getAgentTicket, collaboration notes, external references, KB/AI 및 extension operation만 사용한다.
- Gates UI-001~006, DOC-001; 기존 draft/command 회귀.

## Actor and source

STAFF/AGENT_WORKSPACE의 기존 READ/UPDATE와 extension access/resource constraint. 탭 선택은 presentation state이며 권한·조회 목적·interaction ID·audit source를 바꾸지 않는다.

## Product and UX contract

- 기존 #158의 64px rail/320px properties/기본 접힌 context drawer를 최신 main/P09와 통합한다.
- 고객·협업·자료 탭을 제공하고 header 협업 작업은 협업 탭을 연다. #collaboration 링크도 같은 경로를 쓴다.
- 고객/관계/최근 활동을 먼저 읽고 AI/KB는 자료에서 연다. 최근 활동은 반환된 staff-safe history의 최신 4건이며 전체 보기는 현재 받은 목록만 펼친다. 보호된 audit 상세 API를 대신하지 않는다.
- 탭 이동/패널 닫기에서 입력 상태를 유지하고 숨긴 패널은 키보드 탐색에서 제외한다. Escape는 가장 안쪽 열린 dialog를 닫고 trigger로 복귀한다.
- loading/error/denied/empty는 기존 각 기능의 표시를 유지한다.

## Reuse plan

Reuse: 문서화된 canonical SeedTabs, SeedButton, SeedContextCard, SeedDrawer 및 기존 property/conversation/composer 계약.
Compose: feature-owned context tab 상태와 기존 고객·관계·활동·협업·자료를 조합한다.
Extend: SeedTabs.items.panelId의 선택적 탭/패널 접근성 연결, SeedWorkspaceHeader.actions 및 SeedDrawer.keepMounted의 선택적 presentation API를 문서화한다. 기존 탭 사용과 기본 drawer lifecycle은 유지한다.
Add: 독립 component/일반화된 panel registry 없음.

## In scope / Out of scope

P10 문맥 사용 여정과 기존 #158 visual baseline 검증. 열린 티켓 탭/resize/Cmd+K는 P11, AI eligibility는 P13, presence reconnect는 P14, 매크로 목록은 루트 P12에서 수행한다. HTTP/API/DB/권한 및 backend 구현은 변경하지 않는다.

## Invariants and failure semantics

PUBLIC/INTERNAL 분리와 draft, 이관/child ownership, active-group assignee, ticket row source of truth, command transaction/audit/version/idempotency를 유지한다. 문맥 이동만으로 mutation을 실행하지 않는다. optional extension의 기존 permission/error isolation을 유지한다.

## Data and privacy

현재 staff projection만 정렬·표시한다. 신규 저장/로그/retention/export/secret 노출이나 외부 I/O는 없다. PII를 탭 식별자나 URL로 추가하지 않는다.

## Threats changed

숨긴 tab의 focus 누출, context 중복 mount, 탭 전환/닫기 입력 손실 및 오래된 활동 표시를 검증한다. 권한/SSRF/재전송 경계는 기존 구현이다.

## Acceptance scenarios

1. 고객 문맥을 열면 AI 카드 스크롤 없이 고객/관계/최신 활동을 읽는다.
2. 협업 작업과 #collaboration 링크는 협업 탭을 열며 탭 이동·닫기/재열기에도 내부 작성 내용이 유지된다.
3. 6개 history fixture에서 최신 4개를 내림차순 표시하고 전체 6개를 펼친다. 원본 history는 변경하지 않는다.
4. 자료 탭의 KB 삽입은 기존 PUBLIC/INTERNAL 초안을 보존한다.
5. 1280/1440/1920에서 대화·배정/저장 작업에 접근하고 drawer keyboard/focus/Axe와 canonical visual을 확인한다.

## Validation

Storybook MCP instructions/docs, focused/full run-story-tests, changed/preview links; staff unit/typecheck/lint/build/boundary, Playwright integration/Axe and reviewed Darwin/Linux visual baselines; docs-check. Backend/PostgreSQL/production deployment 및 수동 스크린리더는 범위 밖이다.

## Compatibility and migration

기존 #158 head를 보존한 병합 commit. P09 #255 선행을 PR에 명시한다. 선택적 DS API는 default behavior가 호환된다. DB/OpenAPI/backfill 없음, frontend revert로 rollback.

기존 #158의 docs/assets 참고 PNG 2장은 최신 main의 승인 자산 경계와 충돌하므로 현재 트래킹에서 제거하고 design-qa의 고정 커밋 링크에 보존한다. 현재 검증 근거는 canonical E2E baseline과 Storybook이다. 문서 검사 allowlist는 완화하지 않는다.

## Human explanation

긴 stack을 세 문맥으로 구분하고 현재 권한과 데이터 경계를 재사용한다. 과거 활동보다 현재 작업에 필요한 정보를 먼저 노출한다. 성능 개선 수치는 측정하지 않는다.

## Completion report

- 구현: 기존 #158 위에 최신 main/P09를 병합하고 고객/협업/자료 탭, 협업 바로 열기, 최신 4건/반환 목록 펼치기를 추가했다. 단일 context mount와 hidden 패널 focus 제외로 작성 내용을 보존한다.
- Passed: Storybook MCP 전체 61 files/298 stories 및 마지막 fixture focused story, staff unit 43 files/263, typecheck/lint/build/boundary, docs-check, Chromium 개발용 E2E 23. 1280/1440/1920에서 context keyboard/focus/page Axe를 확인했다.
- Darwin Queue/Workspace 6장의 실제 변경(diff와 렌더)을 확인하고 검토용 기준선을 갱신했다. Linux 실제 렌더/CI와 사람의 최종 시각 승인은 pending이다. P08 #246의 별도 Queue 변경과 통합할 때 합쳐진 화면의 기준선을 다시 확인해야 한다.
- 전체 E2E 추가 실행은 28 pass/1 skip/1 fail: 변경하지 않은 고객 magic-link 테스트가 비밀번호 모드에서 링크 전송 버튼을 찾아 실패했다. 고객 담당에 전달했으며 이 PR에서 고객 계약/테스트를 변경하지 않았다.
- 초기 Storybook 서버는 여러 무거운 검증을 병렬 실행한 뒤 heap exhaustion으로 종료했다. 8GB heap 서버 재시작/직렬 검증 후 위 최종 결과를 얻었다. 이를 제품 메모리 성능 근거로 사용하지 않는다.
- 미실행: backend/실서버/API/DB/production 배포, 수동 스크린리더, 성능 측정. 다중 티켓 탭/resize/Cmd+K와 AI/presence 개선은 후속 slice다.
- 공개/내부 projection, draft 저장, actor/source/access audit, scope, ticket transaction/version/idempotency, privacy/retention은 변경하지 않았다. 새 API/migration/secret/external I/O 없음.

Storybook preview: [전체 변경](http://localhost:6006/?statuses=affected;modified;new), [최근 활동](http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--latest-activity), [문맥 입력 보존](http://localhost:6006/?path=/story/06-domain-workspace-agentticketeditorworkspace--context-preserves-work), [Drawer 보존](http://localhost:6006/?path=/story/03-components-seed-surfaces--retained-drawer), [대화 중심 화면](http://localhost:6006/?path=/story/07-screens-agent-ticket-workspace-page--conversation-focus), [탭 키보드 계약](http://localhost:6006/?path=/story/02-primitives-seed-core--keyboard-tabs).
