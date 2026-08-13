# Implementation Brief — Canonical Storybook Design System Catalog

## Goal

사람과 AI 에이전트가 현재 Deskseed 디자인 시스템의 토큰, 공용 컴포넌트, 업무 패턴, 상태, 키보드 계약을 Storybook에서 검색·검증한 뒤 실제 Agent Queue와 Ticket Workspace를 구현할 수 있게 한다.

## Decision and source references

- Decision IDs: D-030, D-031, D-032, D-053
- Accepted ADRs: 0019, 0020, 0021, 0039
- Requirements: REQ-UI-001, REQ-UI-002, REQ-UI-003, REQ-UI-005, REQ-UI-006
- Screens: AGT-001, AGT-003, AGT-004
- API operations: unchanged
- Verification gates: UI-001, UI-002, UI-003, UI-004, UI-005

## Actor and source

- Actor: authenticated `STAFF` (`AGENT` or `ADMIN` with `AGENT_WORKSPACE`) for product examples; Storybook fixtures have no authenticated actor and perform no sensitive reads.
- Source: `AGENT_WORKSPACE` for documented product intent; isolated stories use deterministic local fixtures.
- Resource constraints: existing server authorization remains authoritative; Storybook never grants or simulates backend permission.
- Interaction semantics: Storybook navigation, prefetch, and fixture rendering do not emit `TICKET_VIEWED` or any access audit event.

## Product and UX contract

- Canonical implementation and CSS root remain `frontend/src/design-system/` and `frontend/src/design-system/index.css`.
- Storybook hierarchy is `01 Foundations` through `07 Screens`.
- Documentation explains when and why to use a contract, supported states, and misuse boundaries.
- Interactive stories verify tabs, drawer focus/close, queue sorting/selection/keyboard movement, and PUBLIC/INTERNAL draft separation.
- Component-level axe and Storybook tests fail on regressions; Playwright retains page-level visual ownership.

## In scope

- Existing Storybook configuration hardening and global production provider/CSS reuse.
- Foundations, primitives, components, patterns, AgentShell, ticket-workspace domain components, and current screen composition stories.
- Deterministic args, controls, interaction tests, accessibility checks, Storybook build, and current frontend verification gates.
- Documentation of missing/deferred contracts without inventing product states or routes.

## Out of scope

- A new design system, ThemeProvider, token scale, icon library, or component API.
- Customer Portal shell/page, Admin, Audit, Search, Integration, SLA, or ticket mutation production routes deferred by ADR 0039.
- Backend, OpenAPI, migration, permission, audit, idempotency, or concurrency behavior changes.
- Playwright baseline regeneration.

## Invariants and failure semantics

- PUBLIC and INTERNAL remain textually and semantically distinct; drafts remain separate in the documented composer flow.
- Customer-only projections and internal child/audit data are never invented or exposed in a customer story.
- Story fixtures are static, synthetic, and network independent.
- Missing documented capability is reported as a gap rather than implemented as a Storybook-only production feature.

## Data and privacy

- Stories use committed synthetic names, emails, ticket numbers, timestamps, and images only.
- No password, token, session cookie, authorization header, search query, or comment from a real environment is read or stored.
- No retention, export, webhook, or audit ledger effect.

## Acceptance scenarios

- Given a developer needs a Deskseed color or spacing contract, when Foundations is opened, then semantic color roles and the canonical implementation scales are visible with usage boundaries.
- Given a developer needs a control or status pattern, when component docs are searched, then supported props, meaningful variants, and intended usage are documented without invented props.
- Given a keyboard user tests tabs, drawer, or ticket table, when the related play test runs, then focus/selection/close/sort behavior and callback outcomes are asserted.
- Given an Agent Workspace fixture, when PUBLIC and INTERNAL modes are switched, then each draft is preserved and the accessible mode announcement remains distinct.
- Given the Storybook suite runs, when an accessibility violation occurs, then the component test fails rather than being silently disabled.

## Validation

- Storybook MCP `run-story-tests` focused and complete with accessibility enabled.
- Storybook MCP `get-changed-stories` and `preview-stories` for representative affected stories.
- `npm run typecheck`
- `npm run lint`
- `npm test`
- `npm run build-storybook`
- `npm run check:design-system-boundaries`
- Related Playwright current-surface specs without snapshot update.

## Compatibility and rollback

- OpenAPI classification: no change.
- Migration/backfill: none.
- Production component APIs and runtime routes remain compatible.
- Rollback is a source revert of Storybook/docs configuration and stories; no data rollback exists.

## Human explanation

Storybook becomes the searchable documentation and isolated verification layer while production code remains the canonical implementation. Page-level Playwright baselines remain separate so component catalog expansion cannot silently redefine full-screen acceptance.
