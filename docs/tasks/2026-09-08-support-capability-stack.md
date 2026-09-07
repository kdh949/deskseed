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

각 행은 이전 행의 branch를 base로 하는 PR이다. 행 안에서도 사용자 흐름별로 커밋하며, 계약·구현·회귀 테스트·추적 문서는 해당 커밋에 포함한다. 계획 승인을 다시 기다리지 않고 모호한 구현 선택은 기존 Accepted 계약과 최소 변경 원칙으로 결정한다.

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
- [ ] collaboration
- [ ] macros
- [ ] knowledge
- [ ] automation
- [ ] 전체 검증 및 Stacked PR 게시

## Slice 1a — administrator field and form operations

- Added ADMIN field/option create, edit and activation; form placement, actor policies, fact-equals conditions, draft/publish/archive routes. Reuses frozen APIs, CSRF and staff actor snapshot transport.
- Request bodies include only operation-owned fields; immutable identities are excluded from update bodies. No backend/migration change in this slice.
- Passed: frontend typecheck; staff unit suite (32 files, 209 tests); focused Storybook MCP tests including create/conflict/empty/denied/error/loading; staff production build; design-system boundary checks. UI-002/004 evidence.
- Customer submission, agent configuration, tags/statuses and View filters remain pending in subsequent slices. Backend CFG gates are not rerun for this frontend-only slice.

- Final slice verification: full staff Storybook MCP suite 71/71 PASS (fresh test process); 1280/390/320px rendered conflict state has no horizontal overflow. `validate_documentation.py`, ESLint and `git diff --check` PASS.
