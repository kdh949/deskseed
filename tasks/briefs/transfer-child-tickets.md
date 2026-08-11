# Task Brief — Ticket Transfer and Internal Child Collaboration

## Goal

상담사가 기존 티켓의 소유권을 명시적으로 이관하거나, 부모 소유권을 유지한 채 다른 그룹에 고객 비노출 내부 child task를 위임하고 관계·감사·해결 경고를 한 흐름에서 확인한다.

## Decision and source references

- Decision IDs: D-003, D-004, D-005, D-018, D-031, D-041, D-042
- Accepted ADRs: 0003, 0004, 0005, 0018, 0020, 0030
- PRD/domain: `docs/01-prd-mvp.md` M4/M5/10~12, `docs/02-domain-model.md` 3/4/6/7
- API operations: `transferAgentTicket`, `createChildTicket`, `getAgentTicket`, `updateAgentTicket`
- Verification gates: ARCH-002, ARCH-004, TKT-002~005, CHG-001~004, PERM-001, PERM-002, UI-001~005

## Actor and source

- Actor: authenticated active `STAFF` (`AGENT` or `ADMIN`)
- Source: `AGENT_UI`
- Required role: Admin may mutate every operational ticket; Agent transfer/child creation requires current assignee or active membership in the current ticket group.
- Resource constraints: read remains `ALL_TICKETS`; write remains independently `GROUP_OR_ASSIGNEE`. Relation-based parent/child READ grants are persisted and tested for a future restrictive read scope but are redundant in the launch preset.
- Request/correlation: accepted bounded request/correlation IDs and one command ID are copied to every audit produced by the command.

## Product and UX contract

- Requirements: REQ-TKT-011~012, REQ-CHILD-001~007, REQ-AUD-001, REQ-AUD-007, REQ-PERM-001, REQ-UI-003~005
- Screens/routes: AGT-004, AGT-006, `/agent/tickets/:ticketNumber`
- OpenAPI operations: `transferAgentTicket`, `createChildTicket`, `getAgentTicket`, `updateAgentTicket`
- Zendesk-inspired pattern: right context panel lists parent/children and exposes distinct Transfer/Create child dialogs without proprietary assets.
- States: dialog validation, submitting, denied, stale/precondition, server error, and success refresh; empty parent/children state; solve warning notification.
- Accessibility: dialog focus entry/trap/escape/restore, labelled fields, keyboard-accessible related-ticket links and actions, non-color warning text.
- Visual regression: AGT-004 open-child fixture at 1440x900 plus existing 1280/1440/1920 workspace baselines.

## In scope

- forward-only `ticket_relations` Flyway migration using explicit `PARENT_CHILD`
- separate `TransferTicket` and `CreateChildTicket` application commands
- active target group/member validation and current-ticket write authorization
- parent-one/children-many, depth-one, self-link and cycle rules
- INTERNAL-only child creation with a separate ticket and immutable audit
- parent relation audit and unchanged parent group/assignee
- staff detail parent/children/open-child projection and future relation READ-grant query/policy seam
- structured non-blocking parent-solve warning and no child-to-parent transition
- customer API/DOM/direct-number non-discovery regressions
- workspace context list, Transfer dialog, Child dialog, warning rendering
- migration, OpenAPI, backend/frontend/component/browser/accessibility/visual tests

## Out of scope

- child PUBLIC reply workflow or customer projection
- automatic parent solve/reopen from child status
- generic `parent_id` column, arbitrary relation types, relation deletion UI
- configurable group permission matrix or changing the launch `ALL_TICKETS` preset
- copy-context payloads, due hints, attachments, email/webhook/network I/O

## Invariants and failure semantics

- Transfer preserves ticket ID/number, changes only the existing ownership, and records previous/next group and assignee plus the actor in one ticket audit.
- Create child never changes parent group/assignee. It creates one `INTERNAL_CHILD`, one INTERNAL first comment, one `PARENT_CHILD` relation, one child audit, and one parent relation audit in one transaction.
- A child has at most one parent; relation depth is one; self-link, duplicate, and cycle candidates are rejected.
- The target assignee is optional but, when present, must be an active member of the active target group.
- Both commands re-check current ownership authorization and exact expected parent/ticket version. A stale `If-Match`/body version returns a precondition problem and commits nothing.
- Solving a parent with open children commits and returns a structured warning with count and ticket numbers. Solving a child never updates the parent.
- Audit/relation/comment/ticket failure rolls back the whole command. No external I/O occurs.
- Staff commands have no replay persistence yet; clients use a command ID, and a network retry must re-read the current ETag before resubmission.

## Data and privacy

- Writes: ticket ownership/current status, child ticket, INTERNAL comment, typed relation, structured ticket audits/events.
- Reads: staff projection includes parent/children; customer projection remains kind-filtered and PUBLIC-only.
- PII: child reuses the parent requester reference for internal context but is excluded from every customer projection. Comment body is not duplicated in audit; only bounded metadata/hash is recorded.
- Retention: child/support content follows support-record policy; ticket audits follow ticket-audit retention; relation follows retained tickets.
- Export/webhook: unchanged and out of scope. No relation or child data enters customer responses.

## Threats changed

- cross-group mutation through global read
- inactive/wrong-group assignee
- stale transfer or duplicate child creation
- relation self-link/cycle/depth escalation
- customer discovery by API shape, DOM, parent token, or guessed number
- PUBLIC child comment creation
- audit omission or partial parent/child commit
- accidental parent solve/reopen propagation

## Acceptance scenarios

- Given an authorized owner, when Transfer is submitted, then the same ticket number has the target ownership and one audit contains structured group/assignee diffs; no new ticket exists.
- Given an authorized parent owner, when Child creation is submitted, then parent ownership is byte-for-byte unchanged, the child has target ownership and an INTERNAL first comment, and parent/child audits plus relation commit atomically.
- Given invalid membership, stale version, unrelated read-only Agent, child-as-parent, self/cycle candidate, or audit failure, then no partial ticket/relation/comment/audit mutation commits.
- Given a child exists, when customer APIs/DOM use the parent token or the child number is guessed, then child/relation/internal data is absent and discovery receives the generic not-found boundary.
- Given open children, when the parent is solved, then the parent commits SOLVED and returns the child count/numbers warning while child status stays unchanged.
- Given a child is solved, then its parent status/version does not change.
- Given launch `ALL_TICKETS`, an unrelated active Agent can read both tickets; a modeled relation READ grant is also true for the child group/assignee but does not grant parent write.

## Validation

- TKT-002~005, CHG-001~004, PERM-001/002, UI-001~005
- `cd backend && ./gradlew test`
- `cd frontend && npm test`
- `cd frontend && npm run build`
- `cd frontend && npm run test:e2e:dev`
- `cd frontend && npm run test:e2e:stack`
- OpenAPI lint/validation and Flyway empty/upgrade migration tests from the repository verification tasks

### Known release gate

- `npm audit --audit-level=high` on 2026-08-11 reports two high and one moderate advisory in the pre-existing, unchanged lockfile (`react-router` and the `styled-components` → `postcss` chain). This slice adds no package and does not add SSR/RSC execution or untrusted CSS processing, but the dependency upgrade remains a release blocker and must be resolved or explicitly risk-accepted by the human owner before release.

## Compatibility and migration

- OpenAPI: pre-release M4/M5 outline is frozen to explicit Transfer/Child request, warning, ETag, and child-audit result shapes.
- Migration: additive forward-only relation table/indexes/constraints; no backfill because no existing child relations exist.
- Rollback: disable new UI/API paths and roll application back; retain the additive table or restore/forward-fix rather than dropping relation history.
- Existing customer and M3 Agent APIs remain additive-compatible.

## Human explanation

- Transfer and child delegation answer different accountability questions, so separate commands and audit shapes prevent ownership history from becoming ambiguous.
- Two ticket audits for child creation are the smallest faithful representation of one command affecting two ticket timelines while current ticket rows remain sources of truth.
- The relation table makes semantics and future permission grants explicit without a hidden `parent_id` shortcut.
- PostgreSQL constraints plus application graph checks are sufficient for depth one; measured requirements for arbitrary relation depth would justify a new model and ADR.
