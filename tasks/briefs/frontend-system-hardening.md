# Implementation Brief — Frontend System Hardening (Stack B PR 3/3)

## Goal

고객·관리자·상담사가 같은 Deskseed 브랜드와 상태·접근성 규칙을 공유하고, 핵심 화면의 시각 회귀를 결정론적 fixture로 검증한다.

## Decision and source references

- Decision IDs: D-030, D-031, D-032, D-041, D-042, D-047
- Accepted ADRs: 0019, 0020, 0021, 0030, 0035
- Requirements: REQ-UI-001~006, REQ-PERM-001/002, REQ-TKT-003/007/008
- Screens: AGT-002, AGT-003, AGT-004, ADM-002, ADM-003, PUB-001, PUB-002
- API operations: unchanged; existing customer, agent-read, and admin contracts only
- Gates: UI-001~005, PERM-001/002

## Actor and source

- Actors: anonymous `CUSTOMER`, authenticated active `STAFF` (`AGENT`/`ADMIN`).
- Sources: `CUSTOMER_PORTAL`, `AGENT_UI`, `ADMIN_UI`.
- Roles/resource constraints: existing server-side authorization and projection rules are unchanged.
- Interaction semantics: background fixtures and visual checks never call sensitive APIs or emit semantic `TICKET_VIEWED`; real Agent Workspace navigation keeps the existing interaction ID behavior.

## Product and UX contract

- Reusable inventory: AppShell, NavRail, WorkSidebar, TicketTabs, TicketTable, SplitPanel, PropertyPanel, ConversationTimeline, ContextPanel, notification/error and unified state primitives.
- Garden imports are confined to `shared/ui/garden`; Deskseed wrappers own product styling and names.
- Loading, empty, error, denied, not-found, stale and conflict variants share one state vocabulary.
- PUBLIC reply and INTERNAL note seams expose text, icon and ARIA announcements and keep mode-specific drafts separate; no write API is added.
- Deterministic visual routes cover Agent Home, View Queue, Workspace, Admin, and public form/detail at 1280, 1440 and 1920 where required.
- Keyboard checks cover skip links, logical focus order, table row open, tabs, and resize handles. Axe must report zero violations for the fixed fixtures.

## In scope

- Central typography, spacing, density, border, focus, status and z-index tokens.
- Deskseed shell/workspace component extraction and current-page adoption.
- Development-only deterministic fixture routes and screenshot baselines.
- Component, keyboard, axe, visual and build verification.
- Visual threshold/change-control documentation, requirement traceability evidence and Garden notice verification.

## Out of scope

- Ticket mutation, reply submission, transfer, child creation or any new business workflow.
- API/OpenAPI, database migration, backend state machine, audit semantics or authorization changes.
- Zendesk marks, screenshots, illustrations, copied CSS or pixel-level cloning.
- Dark theme, mobile Agent Workspace, custom view builder and new Garden major dependencies.

## Invariants and failure semantics

- Customer DOM/API contains only PUBLIC conversation data and no child, staff-only or audit metadata.
- `ALL_TICKETS` read does not grant cross-group write, Admin, Audit Explorer, export or secret access.
- Visual fixture data is synthetic, fixed and independent from network calls.
- Existing route failures preserve safe request IDs and recovery actions.
- Resize preferences remain bounded and keyboard operable.
- No external I/O, transaction, idempotency or retry behavior is introduced by this UI-only slice.

## Data and privacy

- Product routes read the same existing projections; no new persisted field is added.
- Fixture names, emails, tickets and timestamps are synthetic.
- Screenshots contain no real customer data, credential, access token or internal environment detail.
- Browser storage remains limited to layout preferences and future mode-separated draft seams; no secret is stored.

## Threats changed

- Accidental public/internal disclosure: text, icon, accessible mode announcement and separate draft seam.
- XSS/data leakage: fixture content is static text and customer/staff projections remain separate.
- Authorization bypass: fixture routes exist only in development builds and make no API calls.
- UI denial ambiguity: denied/not-found/error variants use explicit semantics without leaking protected existence.
- Trademark/trade-dress risk: Deskseed-only brand mark, wording and semantic palette; Garden license notice retained.

## Acceptance scenarios

- Given any core screen state, when loading/empty/error/denied/conflict is rendered, then it uses the shared state primitive with an appropriate live-region role and recovery action.
- Given keyboard-only navigation, when the user tabs from the document start, then the skip link is first, focus remains visible, rows/tabs/actions are reachable, and resize handles respond to arrow keys.
- Given the reply mode seam, when PUBLIC and INTERNAL are switched, then label, icon and ARIA announcement all change and each mode retains its own draft.
- Given visual fixtures at required widths, when Playwright captures them, then deterministic baselines match within the documented threshold and no snapshot is updated implicitly.
- Given the production build, when assets/imports are inspected, then no Zendesk proprietary mark or screenshot is bundled and Garden imports occur only under `shared/ui/garden`.

## Validation

- `npm run lint`
- `npm run typecheck`
- `npm test`
- `npm run build`
- `npm run test:e2e`
- source/asset/license scans documented with UI-001~005 evidence

## Compatibility and migration

- OpenAPI classification: no change.
- Database migration/rollback/backfill: none.
- Existing routes and API clients remain compatible; rollback is a normal code revert plus visual baseline revert.

## Human explanation

The slice centralizes rendering semantics without introducing a new application state layer. Server data remains in TanStack Query, navigation stays in the URL, and local layout/draft seams remain local. This is the smallest boundary that lets all three product surfaces share a coherent system while preserving the existing backend and security contracts.
