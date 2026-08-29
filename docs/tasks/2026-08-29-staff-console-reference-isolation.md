# Staff Console Reference Isolation Task Brief

## Goal

상담사가 Deskseed 브랜드의 하나의 canonical 디자인 시스템으로 로그인하고, 저장 보기 큐를 탐색하고, 티켓을 검색·생성·처리할 수 있으며, 다섯 개 실제 route와 Storybook이 첨부 시안만을 시각 기준으로 공유한다.

## Decision and source references

- Decision IDs: D-003, D-007, D-030, D-031, D-032, D-033, D-041, D-042, D-047, D-053, D-061
- Accepted ADRs: 0003, 0007, 0019, 0020, 0021, 0035, 0039
- Product sources: docs/01 sections M2/M3, docs/28~31, docs/39, docs/51, docs/55
- OpenAPI operationIds: `getStaffCsrfToken`, `createStaffSession`, `deleteStaffSession`, `getCurrentStaff`, `listAgentViews`, `listTicketsInView`, `searchAgentWorkspace`, `searchAgentCustomers`, `listTicketAssignmentOptions`, `createAgentTicket`, `getAgentTicket`, `updateAgentTicket`
- Verification gates: UI-001, UI-002, UI-003, UI-004, UI-005, UI-006, ACC-001, ACC-003, ACC-004, TKT-001, TKT-006, PERM-001, PERM-002

## Actor and source

- Actor: authenticated STAFF with role AGENT or ADMIN and `AGENT_WORKSPACE`
- Source: `AGENT_WORKSPACE`
- Resource constraint: server-authorized staff ticket projection; global read does not imply cross-group write
- Request semantics: expected-staff actor guard confirms but never selects the server session actor; navigation reads emit one semantic access event, background refresh does not

## Product and UX contract

- Requirements: REQ-AUTH-005, REQ-AUTH-006, REQ-UI-002, REQ-UI-003, REQ-PERM-001, REQ-TKT-006, REQ-TKT-007, REQ-TKT-009, REQ-TKT-010, REQ-TKT-013, REQ-TKT-014, REQ-SRCH-001, REQ-SRCH-002, REQ-VIEW-001
- Routes: `/agent/login`, `/agent/views/:viewKey`, `/agent/search`, `/agent/tickets/new`, `/agent/tickets/:ticketNumber`
- Visual references: the five images attached to the 2026-08-29 task; layout and hierarchy only
- Required states: loading, empty, error, denied, validation, not-found, stale/conflict, responsive restriction
- Accessibility: WCAG 2.2 AA, keyboard reachability, visible focus, manual tab semantics, drawer focus restoration, text labels for status and PUBLIC/INTERNAL mode

## In scope

- Replace the staff foundations and reusable presentation hierarchy under `frontend/apps/staff-console/src/design-system/`.
- Compose the five routed screens and their deterministic Storybook states from the same source.
- Preserve routing, session, API/type, state, validation, command, audit-intent, and accessibility behavior only.
- Add a dependency-boundary proof that the five route/story entrypoints import no retired staff presentation.
- Delete retired visual files after their last consumer is migrated.

## Out of scope

- SSO, forgot-password, customers, analytics, knowledge, automation, reports, apps, save-search, unsupported ticket fields, arbitrary column configuration, and other controls shown only in the references.
- New backend endpoints, OpenAPI fields, migrations, or inferred customer/profile data.
- Screenshot crops, copied proprietary assets/CSS, and Zendesk branding.

## Invariants and failure semantics

- Ticket body remains the first ordered comment; no `Ticket.description` is introduced.
- PUBLIC and INTERNAL drafts remain separate and are never merged by tab switching.
- Same-field conflict preserves local draft and provides explicit recovery; ambiguous retry keeps the command identity.
- Assignee choices remain active members of the selected group.
- Search query remains in POST body/component memory and search-origin linkage is forwarded on deliberate ticket open.
- Required access/change audit failure never degrades to a successful protected response.

## Data and privacy

- No password, session cookie, CSRF value, raw search query, comment body, or attachment content is persisted in visual fixtures or logs.
- Storybook uses deterministic synthetic data only.
- Customer and staff application assets, CSS, tokens, stories, and imports remain isolated.

## Acceptance scenarios

1. Given an anonymous staff browser, when credentials are rejected, the login route shows a generic recoverable failure without account enumeration.
2. Given an authorized agent, when a saved view loads, the route exposes server-owned views/counts and a dense keyboard-navigable ticket table.
3. Given an authorized agent, when a POST-body search returns no rows, the search route retains the query/filters and shows an accessible empty recovery state.
4. Given invalid create-ticket input, when submit is attempted, the route preserves values, links errors to fields, and sends no command.
5. Given a write-capable ticket, when PUBLIC and INTERNAL modes are switched, both drafts survive independently.
6. Given a same-field 409, when the ticket workspace renders recovery, the local edit remains visible and no duplicate comment is implied.
7. Given a viewport below the supported staff width, the route exposes a clear limited-layout state or drawer composition without hiding status/ownership controls.

## Validation

- Storybook MCP: `get-changed-stories`, focused `run-story-tests` with a11y, full `run-story-tests`, and `preview-stories` for the five screen stories.
- Package gates from `frontend/`: `npm run test:staff`, `npm run build:staff`, `npm run check:design-system-boundaries`.
- Browser gates: targeted Playwright route/keyboard/accessibility flows and 1672x941 plus 1448x1086 reference-state captures; required 1280/1440/1920 staff snapshots where applicable.
- Static proof: active route/story dependency graph contains zero retired token, CSS, component, shell, wrapper, class-name, and screenshot imports.

## Compatibility and migration

- OpenAPI change classification: none.
- Database migration: none.
- Route URLs and non-visual client contracts remain compatible.
- Retired presentation is deleted only after all active consumers move to the new canonical public surface.

## Human explanation

The visual rewrite changes only presentation ownership. Server projections, actor authorization, audit intent, command identity, concurrency, and privacy boundaries remain authoritative, so a reference-only control is omitted instead of being simulated.
