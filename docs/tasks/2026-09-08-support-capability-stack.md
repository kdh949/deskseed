# 상담 운영 기능 Stacked PR 구현 계획

## Goal

관리자가 문의 정책을 설정하고 고객·상담사가 폼, 내부 협업, 매크로, 지식, 자동화로 실제 문의를 처리한다. 사용자 요청에 따라 구현·수직 슬라이스 커밋·push·Stacked PR 게시까지 수행한다.

## Decision and source references

- D-003/004/007/018/032/033/035/055/056/059/061/062, ADR 0004/0007/0018/0024/0040/0041/0044/0045 유지.
- PRD 01, domain 02, architecture 03, rules 07, requirements 26, handbook 27, schema 32, permissions 33, state machines 34, contract 39, frontend 28–31/40/51, specifications 45/47/48, runbook 50, settings 52.
- 현재 main: 6ed6db32f7d9d0da0cfe223871c20a27ba8a54c0. 별도 worktree에서 진행하며 기존 PR #158의 시각 개편은 포함하지 않는다.

## Ordered vertical slices

| Stack | 사용자 결과 / 커밋 경계 | Requirement / Gate | 검증 조건 |
|---|---|---|---|
| 1 configuration | 필드·선택지 관리 → 조건부 폼 편집·발행 → 고객 접수·상담사 값 편집 → 태그·상태·View 조건 연결 | REQ-CFG-010~014, REQ-VIEW-001; CFG-001~006, UI-002/003/004/006 | 서버 projection 사용, 숨김 필드 제외, stale 시 입력 보존, 권한 거부, 접수 값 전달 |
| 2 collaboration | 단일 티켓 이관 → 부모 소유권을 보존하는 자식 생성·관계 표시 | REQ-TKT-012, REQ-CHILD-001/003/006; CHG-001, CONC-001, UI-002/003/004 | 활성 그룹·구성원 선택, 동일 command 재시도, 초안 보존, 고객 비노출 |
| 3 macros | 개인·공유 매크로 생성·버전 수정·활성화와 기존 preview/apply 연결 | REQ-CFG-003; AUT-003/004/007/008, UI-002/004 | 소유자/공유 capability, typed action, stale/실패 보존, 공개·내부 명시 |
| 4 knowledge | 문서 계층·초안·검토·발행 관리 → 상담 중 권한 필터 검색·문서 참조 삽입 | REQ-KB-001/002/004; ACC-006/007, FILE-002, UI-002/004 | immutable revision, audience, 권한 거부, 공개 링크 적합성, 실패 시 안전한 상태 |
| 5 automation | 생성/변경 trigger와 조건·액션 확장 → 관리자 dry-run·활성화 → 시간 자동화 설정 | REQ-AUT-001/002; AUT-001~009, ARCH-002/003 | 정상 ticket command, mutation+audit 원자성, outbox, 순서·루프·재시도, dry-run 무변경 |

각 행은 기능군이다. 설정은 관리자 편집과 고객/상담사 runtime 연결로 나누어 PR을 구성한다. 현재 stack은 관리자 설정 → 협업 → 매크로 → 지식 → 고객 문의 접수 → 상담사 설정/태그·상태/View → 자동화 순서로 쌓는다. 행 안에서도 사용자 흐름별로 커밋하며, 계약·구현·회귀 테스트·추적 문서는 해당 커밋에 포함한다. 계획 승인을 다시 기다리지 않고 모호한 구현 선택은 기존 Accepted 계약과 최소 변경 원칙으로 결정한다.

## Actor, data, and failure boundaries

- ADMIN은 설정·발행, STAFF는 허용된 티켓·매크로·문서, CUSTOMER는 서버가 허용한 폼과 자기 문의만 사용한다.
- 기존 session/CSRF/expected staff actor 경로를 재사용한다. UI 숨김이 server authorization을 대체하지 않는다.
- Ticket 본문은 첫 PUBLIC comment, transfer와 child는 별도 command, assignee는 현재 group의 active member다.
- 민감 read의 required access audit, mutation과 change/admin audit 원자성, background read의 semantic view 제외를 유지한다.
- expected version/If-Match 충돌에는 초안을 보존하며 ambiguous command는 동일 identity로 재시도한다. API가 idempotent create를 제공하지 않으면 자동 재시도하지 않는다.
- 외부 I/O는 기존 durable outbox/worker를 사용한다. 신규 분산 시스템·generic workflow engine·임의 코드/SQL 실행은 추가하지 않는다.
- 민감 본문/입력은 ordinary log·URL에 기록하지 않는다. 공개 답변에는 내부 지식·자식 정보가 새어 나가지 않는다.

## UI reuse plan

- Reuse/Compose: app-local documented button, input, select, textarea, checkbox, notice, feedback, drawer, rich-text controls와 기존 레이아웃.
- 기존 카탈로그의 helper 함수로 인해 MCP에 누락된 사용 예시만 명시적으로 문서화한다.
- 새 도메인 화면은 feature contribution과 현재 ADMIN/AGENT 경로를 사용한다. 필요 없는 새 디자인 시스템이나 범용 폼 엔진은 만들지 않는다.
- loading/empty/error/denied/stale 및 keyboard/focus를 story와 회귀 테스트로 검증한다.

## Scope decisions

이번에는 선택된 다섯 영역의 일상 운영 흐름을 제공한다. 별도 지표 체계가 필요한 매크로 활용 통계, 문서 검토 기한·검색 무결과 대시보드, 수요 확인 후의 다국어는 후속이다. AI, 채팅, 전화, 고객사, 전체 통계 제품은 이 stack 범위가 아니다.

## Validation and delivery

- 기능별 Vitest와 Storybook MCP run-story-tests; 변경 story 조회·미리보기.
- frontend typecheck, affected app build/test, lint/format, design-system boundaries; 관련 Playwright.
- backend 변경 시 focused PostgreSQL tests, architecture/runtime OpenAPI parity, docs-check.
- 마지막 full Storybook suite와 frontend suite. 검증 결과·Not run은 근거와 함께 기록.
- staged paths 확인 → 한국어 수직 슬라이스 commit → 이전 branch를 base로 PR 생성 → ancestry/원격 CI 확인. merge/deploy는 수행하지 않는다.

## Progress

- [x] 최신 main·기존 PR·dirty worktree 확인, 별도 작업 디렉터리 구성
- [x] Staff/Customer Storybook MCP inventory와 현재 지침 조회
- [ ] configuration
- [x] collaboration
- [x] macros
- [x] knowledge
- [ ] automation
- [ ] 전체 검증 및 Stacked PR 게시

## Slice 1a — administrator field and form operations

- Added ADMIN field/option create, edit and activation; form placement, actor policies, fact-equals conditions, draft/publish/archive routes. Reuses frozen APIs, CSRF and staff actor snapshot transport.
- Request bodies include only operation-owned fields; immutable identities are excluded from update bodies. No backend/migration change in this slice.
- Passed: frontend typecheck; staff unit suite (32 files, 209 tests); focused Storybook MCP tests including create/conflict/empty/denied/error/loading; staff production build; design-system boundary checks. UI-002/004 evidence.
- Customer submission, agent configuration, tags/statuses and View filters remain pending in subsequent slices. Backend CFG gates are not rerun for this frontend-only slice.

- Final slice verification: full staff Storybook MCP suite 71/71 PASS (fresh test process); 1280/390/320px rendered conflict state has no horizontal overflow. `validate_documentation.py`, ESLint and `git diff --check` PASS.

## Slice 2 — single-ticket transfer and internal collaboration

- Added a context contribution with lazy BACKGROUND ticket reads, active group/member selection, transfer reason and INTERNAL child request input. Both commands reuse existing If-Match and stable clientCommandId semantics.
- Ambiguous errors retain the exact submitted payload and command ID; definite version conflict preserves input until an explicit latest-version refresh. Successful commands refresh ticket and View queries, and child success links to the created ticket.
- Related tickets now label parent versus internal collaboration, show the target group/open child count and offer all related tickets beyond the first four.
- Found and fixed a reusable Drawer focus reset: inline onClose callbacks no longer steal focus on input changes. Nested dialogs handle only their own keyboard events; disabled fieldset controls are excluded from focus cycling. Regression stories cover typing and nested Escape/focus restoration.
- Passed: TransferChildTicketIntegrationTest (6 tests), staff unit suite (209 tests), typecheck, staff production build; focused Storybook transfer/retry/conflict/denied/empty/loading/error and drawer regressions. 1280/390/320px actual drawer rendering has no horizontal overflow.
- No API, migration, server authorization, audit, retention or external I/O changes. Customer projection continues to exclude child tickets. Deployment, browser-to-live-backend end-to-end and performance measurement were not run for this slice.

- Final collaboration validation: full staff Storybook MCP 81/81 PASS; six PostgreSQL transfer/child integration tests PASS; documentation/design-system boundary checks PASS.

### Slice 3: 매크로 관리

- 개인/공유 매크로 목록, 문구/공개 범위/상태/우선순위 편집, 저장 미리보기, 명시적 활성/비활성, 버전/활성 이력 조회를 연결했다.
- 기존 고급 typed action은 순서와 내용을 보존하며 기존 티켓 preview/apply를 재사용한다.
- 이력 GET만 additive하게 추가하고 DB/migration/infrastructure 변경 없이 기존 immutable rows를 소비한다.

### Slice 4: 지식 문서 운영

관리자 분류/초안/검토/발행과 상담 지식 검색·읽기·링크 삽입을 연결했다. 기존 문서 형식과 PostgreSQL 검색·required audit를 사용한다. 섹션 목록과 검토/공개 중지 문서의 초안 복귀만 API에 추가했다. 상담 링크 삽입은 현재 초안을 보존하고 문서 audience를 서버에서 재확인한다.

Passed: 관리자 knowledge 통합 5, API 계약 5, architecture 1, staff unit 214, Storybook MCP 전체 99/99 및 a11y, typecheck/build/lint/boundaries/docs. 1280/390/320px 가로 넘침 없음 및 모바일 육안 확인. 실제 백엔드 browser E2E, 운영 부하, 배포는 Not run.

### Slice 5: 조건부 폼을 통한 고객 접수

고객 폼 후보값 판정부터 최종 typed 값·동의·첨부 저장과 안전한 재시도를 구현했다. 고객 UI는 서버의 표시/필수/읽기 전용 판정과 발행 버전을 사용한다. 상세 경계와 검증은 `2026-09-08-ticket-form-runtime.md`에 기록했다. 상담사 설정·태그·상태·View는 다음 PR로 분리한다.
