# 상담 지식 검색과 문서 읽기 전환

## Goal

상담사가 검색 결과 아래까지 스크롤하지 않고 선택한 지식 문서를 읽고, 기존 검색 문맥으로 돌아간다. 감사 AG-F12의 KB 보강 관찰에 대한 P10 후속이다.

## Decision and source references

REQ-KB-002/004, REQ-UI-002/003/004/006; D-030/031/032/033/054, Accepted ADR-0018/0040/0044; docs28~31/40/51/55 및 2026-09-08 knowledge task. `searchAgentKnowledge`, `getAgentKnowledgeArticle`, `suggestAgentKnowledgeForTicket`만 사용하며 새 API/권한/계약 없음. UI-002/003/004/005/006, DOC-001.

## Actor and source

STAFF/AGENT_WORKSPACE의 기존 인증과 지식 audience projection을 사용한다. 문서 선택과 링크 삽입 시 기존 read/audit 경로를 유지하며 검색 원문을 URL/일반 로그에 남기지 않는다.

## Product and UX contract

검색 결과 선택 시 패널을 읽기 상태로 전환해 검색 폼과 목록을 숨기고 읽기 성공 후 문서 제목에 포커스한다. 돌아가기는 검색어·검색 결과·cursor를 보존한 채 선택했던 읽기 버튼으로 포커스를 복원한다. 직접 문서 URL로 진입한 경우 검색 폼으로 돌아갈 수 있다. loading/error/denied에서도 돌아가기 경로를 제공하고 진행 중 요청은 기존 busy 잠금으로 직렬화한다.

Reuse: 문서화된 SeedButton, SeedTextField, SeedNotice, SeedContextCard, SeedDrawer, 기존 KnowledgeDocument renderer. Compose: feature-owned 검색/읽기 전환과 focus. Extend/Add: 없음. 기본 Drawer 크기·스타일·브랜딩과 기존 외부 props는 변경하지 않는다.

## In scope / Out of scope

AgentKnowledgePanel과 해당 story·회귀·작업 문서만 변경한다. API/DB/검색 paging 계약, 새 layout framework, 서버 캐시, 문서 작성, 링크 접근 정책, 다른 앱은 제외한다.

## Invariants and failure semantics

PUBLIC/INTERNAL 링크 구분, 삽입 직전 audience 재검증, 기존 양쪽 composer draft 보존을 유지한다. 현재 article/read state와 검색 state는 패널이 소유하며 권한을 새로 추정하지 않는다. read failure에 보호 문서를 새로 표시하지 않으며 기존 fail-closed audit/renderer/URL 정책을 재사용한다. 티켓 command/idempotency/version, transaction/outbox/retry 변화 없음.

## Data and privacy

새 영구 저장소·PII·log·retention·export 없음. 기존 메모리 상태만 보존하며 고객 API/DOM boundary를 변경하지 않는다. SSRF/XSS/secret/audit boundary 변화 없음.

## Acceptance scenarios

1. 20개 검색 결과 중 문서를 선택하면 검색 폼/목록이 사라지고 제목에 포커스한다.
2. 돌아가면 검색어/결과/cursor가 유지되고 선택했던 버튼에 포커스한다. 검색 요청을 재실행하지 않는다.
3. 조회 실패·권한 거부에서 검색 문맥으로 돌아갈 수 있다.
4. PUBLIC과 INTERNAL 링크 정책, 삽입 전 재인가 및 기존 양 채널 draft 보존 story를 유지한다.
5. 기존 Drawer를 1280/1440/1920에서 keyboard·overflow·Axe로 검증한다.

## Validation

Storybook MCP documentation/instructions/contracts → focused/full run-story-tests, changed/preview. Staff unit/typecheck/lint/build/boundary/docs-check, 기존 Drawer 3폭 렌더·focus·Axe. 실제 backend/운영/부하/수동 screen reader는 실행하지 않는다.

## Compatibility and migration

API/DB migration 없음. 기존 Props 호환, frontend revert로 rollback. REQ 상태는 기존 IN_PROGRESS를 유지한다. 성능 개선 수치는 측정하지 않는다.

## Human explanation

검색 목록과 문서를 동시에 길게 붙이지 않고 동일 패널에서 전환한다. 돌아가기 위해 검색을 다시 보내지 않지만 링크 삽입은 기존 서버 재검증을 그대로 수행한다.

## Completion report

검색/읽기 전환과 결과 복귀를 구현했다. 새 API/DB/권한/감사/transaction/idempotency/retry/retention 변경 없이 기존 문서 조회와 삽입 전 audience 재검증을 유지했다. DS public API 확장이나 새 저장소는 없다.

Passed: 신규 단위 회귀 3개(20건 목록·미적용 입력과 적용 cursor·loading·denied·직접 URL), focused MCP KB 6개와 기존 workspace PUBLIC/INTERNAL 초안 보존·링크 삽입 1개. 기존 Drawer를 Chromium 1280/1440/1920×900에서 렌더링해 제목 focus, Shift+Tab/Enter 복귀, 검색어/선택버튼/cursor, Escape/return focus, bounds/가로 넘침/Axe와 screenshot 3장을 검수했다. 실제 CSS viewport와 이미지 폭은 동일하다. typecheck/lint/format/design-system boundary/docs-check도 통과했다.

전체 staff unit/MCP/build와 전체 E2E는 자원 경합을 줄이기 위해 상위 작업의 최종 통합 검증·원격 CI에서 확인한다. 이 PR의 로컬 focused 검증을 전체 검증이나 운영 검증으로 표현하지 않는다. 실제 backend/운영/부하/수동 screen reader/사용자 최종 시각 승인은 미실행이다.

MCP changed-stories가 32개와 경로 coverage 경고를 반환해 component 경로로 28개 소비 story를 추가 확인했다. 프리뷰:

- http://localhost:6130/?statuses=affected;modified;new
- http://localhost:6130/?path=/story/07-screens-agent-knowledge--drawer-reading
- http://localhost:6130/?path=/story/07-screens-agent-knowledge--search-read-insert
- http://localhost:6130/?path=/story/06-domain-workspace-agentticketeditorworkspace--insert-knowledge-link-preserves-reply
