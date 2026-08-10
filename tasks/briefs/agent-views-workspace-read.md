# Implementation Brief — Agent Views and Workspace Read Path (Stack B PR 2/3)

## Goal

활성 상담사가 모든 staff-visible 티켓을 기본 Views와 직접 URL에서 찾고, Deskseed 3-panel Workspace에서 PUBLIC/INTERNAL 대화와 고객·속성·로컬 이력을 읽는다.

## Decision and source references

- Decision IDs: D-003, D-005, D-013, D-018, D-030, D-031, D-032, D-041, D-042, D-047
- Accepted ADRs: 0003, 0005, 0013, 0018, 0019, 0020, 0021, 0030, 0035
- Requirements: REQ-PERM-001, REQ-TKT-007, REQ-AUD-003, REQ-UI-001~005
- API operations: `listAgentViews`, `listTicketsInView`, `getAgentTicket`
- Screens: AGT-002, AGT-003, AGT-004
- Gates: ARCH-001/002/004, TKT-001/002, ACC-001/002, PERF-001, UI-001/002/004/005, PERM-001/002

## Actor and source

- Actor: authenticated, active `STAFF` with `ADMIN` or `AGENT` role.
- Source: `AGENT_UI`.
- Read scope: initial `ALL_TICKETS`; customer, admin, audit and secret projections remain separate.
- Write scope: unchanged `GROUP_OR_ASSIGNEE`; this slice adds no mutation endpoint or UI.
- Interaction: each intentional navigation creates a UUID. Revalidation reuses it; prefetch is marked `BACKGROUND`.

## Product and UX contract

- Routes: `/agent/views/:viewKey`, `/agent/tickets/:ticketNumber`.
- Views: `my-open`, `unassigned-my-groups`, `pending`, `recently-solved`, `my-child-tasks`.
- Queue order: `(updatedAt DESC, ticketNumber DESC)` with an opaque cursor bound to view and filters.
- Filters: status, priority, group and assignee.
- Workspace: global rail, work navigation, ticket tabs, properties, conversation and context panel.
- States: loading, empty, error, denied and not-found. This read-only slice has no edit conflict state.
- Visual fixtures: 1280x800, 1440x900 and 1920x1080; keyboard row open, tabs and resize handles; axe.

## In scope

- Forward-only migration for canonical access audit and queue indexes.
- Server-side `ALL_TICKETS` read policy seam.
- Bounded default-view queries and stable cursor pagination.
- Staff detail projection with customer, PUBLIC/INTERNAL comments, properties, ticket-local history and empty related-context seams.
- Strict, deduplicated `TICKET_VIEWED` persistence before success.
- Read-only Views and 3-panel Workspace UI.
- Contract, PostgreSQL integration, component and browser tests plus query-plan evidence.

## Out of scope

- Ticket mutation, composer, transfer, child creation, search and Audit Explorer.
- Saved/custom view builder, custom fields, macros, attachments and external apps.
- `OWN_GROUPS`, `ASSIGNED_ONLY` and `EXPLICIT_GROUP_MATRIX` configuration UI.

## Invariants and failure semantics

- `Ticket.description` is never introduced; the conversation starts with the first comment.
- Customer endpoints never expose INTERNAL comments, staff fields, relations or audit metadata.
- Active staff authorization and staff projection happen server-side.
- Sensitive detail read and canonical access audit complete in one transaction; audit failure returns `503` without the body.
- `(actor, ticket, interactionId, TICKET_VIEWED)` is unique. Same-interaction navigation/refetch is a no-op; `BACKGROUND` never records a semantic view.
- Reads have no idempotency key or optimistic concurrency requirement and perform no external I/O.

## Data and privacy

- Queue returns a minimal requester label. Detail returns the ticket-linked customer name/email and comments.
- Access audit stores bounded actor/resource/request/correlation/network metadata, never comment body, credentials, cookies or authorization headers.
- Access metadata follows the 180-day proposal; no retention executor is added in this slice.

## Threats changed

- Authorization bypass: global read is limited to active staff and the staff projection.
- Visibility leak: INTERNAL content exists only on the staff endpoint and is regression-tested absent from customer endpoints.
- Audit bypass/tampering: strict write, unique semantic view and append-only trigger.
- Cursor manipulation: opaque versioned cursor is decoded and checked against view/filter fingerprint.
- N+1/query amplification: fixed bulk queries with query-count assertions and PostgreSQL plan evidence.

## Acceptance scenarios

- Given two active agents in different groups, either can list and directly open the other's staff-visible ticket.
- Given a customer or inactive staff session, agent list/detail is denied without protected data.
- Given equal `updatedAt`, pagination orders by descending ticket number with no duplicate or omission.
- Given one navigation interaction, the first successful detail returns data and writes one `TICKET_VIEWED`; same-interaction refetch writes zero; a new interaction writes one.
- Given access-audit insert failure, detail returns the stable audit-unavailable problem and no success body.
- Given a customer request with an INTERNAL comment, staff detail includes it while customer detail does not.

## Compatibility and migration

- Core Agent API outline is promoted to a frozen internal contract for the three read operations.
- Migration is additive. Rollback is application rollback plus forward migration cleanup after backup; no destructive down migration is shipped.
- Existing customer and admin routes remain compatible.

## Human explanation

The current Ticket row remains the current-state source of truth, while access audit records the separate fact that a human intentionally opened the detail. PostgreSQL JDBC projections keep the read path explicit and bounded; an external search store or event-sourced read model is not justified by current evidence.

## Completion evidence

- Contract: the three Agent read operations are frozen in `api/core-api-outline-v1.yaml`.
- Backend: cross-group `ALL_TICKETS` list/detail, five default Views, stable cursor/filter binding, strict append-only access audit and query-count/plan integration tests.
- Frontend: Deskseed global/work navigation, dense ticket table, URL filters, read-only properties/conversation/context Workspace, user-specific collapse/width preferences and complete loading/empty/error/denied states.
- Visual/accessibility: Views and Workspace baselines at 1280, 1440 and 1920; keyboard row open and separators; axe reports no violations in the fixture flow.
- Non-goals retained: search/search audit, mutation/composer/transfer/child creation, custom View builder, context apps, real child/external-reference projections and latency percentiles.
