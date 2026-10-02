# 상담사 보기·검색 조건 피드백 개선

## Goal

상담사가 보기 목록의 로딩·실패·빈 상태와 내 처리 중 큐의 범위를 이해하고, 검색 조건을 명시적으로 적용해 현재 결과와 구분한다.

## Decision and source references

- Decisions: D-008, D-032, D-033, D-041, D-045, D-048, D-067
- Accepted ADRs: 0014, 0021, 0036, 0050, 0053
- Requirements: REQ-UI-002, REQ-UI-005, REQ-SRCH-001
- Sources: PRD M2/A2, docs 28~31/40/47/51/55, UX audit AG-F03/04/08
- Operations: `listAgentViews`, `listTicketsInView`, `searchAgentWorkspace`
- Gates: UI-002/004/005/006, DOC-001; SEARCH-AUD-001/002 semantics preserved

## Actor and source

- Actor: authenticated active STAFF with AGENT_WORKSPACE, source AGENT_UI.
- Server remains responsible for staff-visible ALL_TICKETS authorization and required access/search audit persistence.
- Explicit search application starts a new interaction and resets opaque cursor history. Paging/retry retains the applied input and interaction. Opening a result retains originSearchEventId.

## Product and UX contract

- `/agent/views/my-open` stays OPEN plus current assignee; labels and empty guidance describe that scope.
- Saved-view list loading/error/denied/empty are distinct from queue loading/error/empty and view-name search with zero matches.
- `/agent/search` keeps draft query/filter/sort separate from applied criteria. All changes apply on explicit submit. Current result criteria and pending changes are visible in text.
- Existing exact/lower-bound/unavailable counts, default latest-first order, too-broad guidance, cursor and result-origin contracts are unchanged.
- Reuse documented Staff Console controls, feedback, filter summary and saved-view navigation; no new generic UI platform.
- Keyboard actions and accessible status text remain usable at 1280/1440/1920.

## In scope

- Queue/search presentation, explicit search interaction, regression unit/stories/browser checks and task evidence.

## Out of scope

- Saved-view backend timestamps: reuse existing PR #238; do not duplicate it or relax strict decoding.
- API fields/endpoints, permissions, cursor/count semantics, DB/migrations, system-view predicates, ticket writes and other UX audit slices.

## Invariants and failure semantics

- No client-side permission substitute. Required server audit failure remains fail closed.
- No new mutation, transaction, external I/O, idempotency or concurrency policy.
- A failed list cannot masquerade as an empty list. Search retry uses last applied criteria; edited draft values remain available.
- No automatic search on every input change and no invented counts.

## Data and privacy

- Search remains POST; raw query never enters URL, local/session storage, ordinary logs or telemetry.
- Staff summaries only; no change to PUBLIC/INTERNAL projections, query encryption, retention, export or webhook exposure.

## Threats changed

- No new authorization/impersonation/replay/SSRF boundary. Verify hidden failures, stale filter confusion and cross-query cursor reuse are not introduced.

## Acceptance scenarios

1. Given OPEN-only default view, its title and empty guidance do not imply NEW/PENDING/ON_HOLD inclusion.
2. Given a view-list loading/403/server error/malformed success/empty array, show its appropriate state while separately fetched queue data remains usable; retry can recover the list.
3. Given applied search results, changing query, filters or sort sends no new search until apply, shows pending changes, and retains an applied-criteria summary.
4. Given page two, applying new criteria resets cursor and interaction; paging without apply preserves old criteria and interaction.
5. Given request failure, retry uses applied input and preserves unsent draft criteria.
6. Exact/lower-bound/unavailable count, broad-query guidance, server row ordering and result-origin handoff remain correct.

## Validation

- Focused unit: AgentViewsPage and AgentSearchPage.
- Required frontend: `npm run test:staff`, `npm run build:staff`, `npm run typecheck`, `npm run check:design-system-boundaries`.
- Storybook MCP: current instructions/contracts, changed stories, focused run-story-tests, relevant previews.
- Playwright: queue/search routes, keyboard and 1280/1440/1920 inspection. Review any intended visual diff before baseline updates.
- Backend/PostgreSQL and production smoke not required for unchanged server behavior; report as not run.

## Compatibility and migration

- No OpenAPI or database change; rollback is frontend commit revert.
- Existing saved-view definitions and strict response decoder remain authoritative.

## Human explanation

Explicit application makes the visible conditions match the result while avoiding a new audited search for each select/keystroke. Existing state owners and contracts are sufficient.

## Completion report

- Implemented OPEN-only queue scope labels, independent list-state feedback/retry, and explicit query/filter/sort application with applied criteria. Added a compatible `SeedSavedViewNavigation.feedback` slot; reused existing feedback, skeleton, notice, button, filter, select and table contracts. No new visual platform or public API endpoint.
- Unit: all 43 staff files / 262 tests passed, including list 403/503/malformed success/retry and search paging/reset/retry/count regressions.
- Storybook MCP: focused 14 stories and full staff suite passed with interaction/accessibility checks. Changed stories and direct consumers were resolved, and relevant previews were returned.
- UI-002/004/005: full development browser suite passed (23 tests), including keyboard application, cursor/interaction preservation, Axe and overflow checks at 1280/1440/1920.
- UI-006: staff build and design-system boundary checks passed. Typecheck and ESLint passed. Existing build chunk-size warning remains; no new performance benchmark is claimed.
- Visual: inspected the intentional queue heading/description shift and updated only three Darwin queue baselines. Workspace baselines are unchanged. Linux queue baseline verification is pending CI rendering; images are not copied between platforms.
- DOC-001: behavior and task documentation updated; requirement implementation statuses are unchanged.
- Not run: backend/PostgreSQL, production smoke/deployment and load tests (server/API/permission/audit contracts unchanged). Backend saved-view response repair remains separate in PR #238.
- No migration, retention, transaction, concurrency, idempotency, permission, or audit changes; rollback is a frontend revert.

Storybook preview URLs returned by MCP:

- http://localhost:6006/?path=/story/07-screens-agent-views-page--queue
- http://localhost:6006/?path=/story/07-screens-agent-views-page--view-list-error
- http://localhost:6006/?path=/story/07-screens-agent-views-page--my-open-empty
- http://localhost:6006/?path=/story/06-domain-workspace-agentsearchpage--pending-conditions
- Fallback for additional changed stories: http://localhost:6006/?statuses=affected;modified;new
