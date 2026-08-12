# Task Brief — Agent Ticket Command, Audit, and Concurrency

## Goal

상담사가 티켓을 직접 만들고, 코멘트와 허용된 티켓 필드를 한 번에 저장하며, 감사 무결성과 필드 단위 낙관적 병합을 보장한다.

## Decision and source references

- Decision IDs: D-003, D-005, D-007, D-018, D-019, D-041, D-042, D-049
- Accepted ADRs: 0005, 0007, 0018
- PRD/domain: `docs/01-prd-mvp.md` M3/10/11/12, `docs/02-domain-model.md` 3/4/7
- API operations: `createAgentTicket`, `updateAgentTicket`
- Verification gates: ARCH-002, ARCH-004, TKT-002, TKT-003, TKT-006, CHG-001~004, IDEM-001~003, PERM-001, PERM-002

## Actor and source

- Actor: authenticated active `STAFF` (`AGENT` or `ADMIN`)
- Source: `AGENT_UI`
- Required role: Agent may create; Admin may update every operational ticket; Agent update requires current assignee or active membership in the current ticket group.
- Resource constraints: read remains `ALL_TICKETS`; write is independently `GROUP_OR_ASSIGNEE`.
- Request/correlation: accepted bounded request/correlation IDs are copied to the single `TicketAudit`; every command has a server-accepted command ID.

## Product and API contract

- Requirements: REQ-TKT-007, REQ-TKT-009~015, REQ-AUD-001, REQ-AUD-007
- Routes: `POST /api/v1/agent/tickets`, `POST /api/v1/agent/tickets/{ticketNumber}/commands`
- Update request: `expectedVersion`, explicit `changedFields`, optional flat field values, optional PUBLIC/INTERNAL comment.
- Conflict: RFC 9457 `409` with `currentVersion` and sorted `conflictingFields`.
- This backend slice does not implement the REQ-TKT-015 conflict banner; the response contract supports the following frontend slice.

## In scope

- forward-only Flyway migration for expected/result audit versions and concurrency lookup
- agent-created ticket and explicit first-comment visibility
- combined update command for status, priority, group, assignee, and comment
- replaceable ticket-write authorization policy
- assignment membership invariant
- structured ordered audit events and append-only protection regression
- field-aware optimistic merge/retry and RFC 9457 problems
- PostgreSQL-backed command, failure, authorization, and concurrency tests
- exact staff UpdateTicket replay from immutable audit metadata, payload/ticket/operation misuse rejection, and database advisory serialization
- V14 partial staff-command audit lookup index plus release-scale exact-query evidence harness
- core OpenAPI synchronization

## Out of scope

- transfer, claim, child ticket, trigger/automation execution
- frontend composer/conflict banner
- Event Sourcing, pessimistic ticket locks, Kafka, external network I/O

## Invariants and failure semantics

- Ticket current row remains source of truth; audit is explanatory history.
- One accepted command for one ticket creates one `TicketAudit`; actual changes create ordered effect events, while a valid pure no-op creates one content-free `UPDATE_COMMAND_RECEIVED` receipt event.
- Ticket/comment/current-version mutation and audit commit in one transaction; required audit failure rolls all of it back.
- A stale request compares its requested field set with field events whose result version is newer than `expectedVersion`.
- Overlap returns 409 before mutation. Non-overlap applies to the latest row.
- A racing optimistic-version failure rolls back the whole attempt and is retried from the latest committed row; retry count is bounded. Same actor/command ID attempts serialize on a PostgreSQL transaction advisory lock before lookup/mutation.
- Comment-only commands do not conflict with metadata fields. Exact same actor/ID/ticket/update descriptor replay returns the original result with no mutation. Different payload/ticket/operation or multiple stored matches returns 409. A pure no-op leaves the ticket version unchanged but retains its replay descriptor on `UPDATE_COMMAND_RECEIVED`.
- If group changes and the existing assignee is not active in the target group, the command must also include an explicit valid `assigneeId` or explicit `null` clear; otherwise the whole command is rejected.
- Closed tickets and unsupported status transitions are rejected.

## Data, privacy, and threat model

- Writes: ticket current fields, immutable comment body, ticket audit metadata, structured event values.
- Comment bodies are not duplicated in audit; event metadata contains ID, visibility, bounded length, and the existing SHA-256 hash. Replay metadata reuses that value and safe field/version values; it adds neither raw body nor a second full-payload digest.
- Customer projection continues to return only PUBLIC comments and customer-safe fields.
- Trust boundary: authenticated HTTP session plus untrusted JSON field/change-set input.
- Abuse cases: cross-group write, changedFields/payload mismatch, inactive assignee, stale overwrite, audit failure, comment leakage, audit update/delete.
- Controls: server-side policy, boundary validation, active-membership lookup, optimistic retry, atomic transaction, append-only DB triggers, projection regression tests.

## Acceptance scenarios

- Given an active Agent, when a staff ticket is created with explicit PUBLIC or INTERNAL first comment, then ticket/comment/one ordered audit commit atomically.
- Given an authorized writer, when comment-only, field-only, combined, or no-op command is sent, then one audit contains only actual ordered events.
- Given an unrelated active Agent who can read the ticket, when update is attempted, then 403 is returned and nothing mutates.
- Given invalid group/assignee membership, when create/update is attempted, then the whole command is rejected.
- Given two requests at one version changing different fields, when committed concurrently, then both changes survive at successive versions.
- Given two requests at one version changing the same field, then one succeeds and the other receives 409 with the latest version and field name.
- Given injected audit insert failure, then ticket, field, comment, and version changes roll back.
- Given runtime SQL UPDATE/DELETE against canonical ticket audit rows, then PostgreSQL rejects it.
- Given exact sequential or concurrent same-ID retry, then one comment/audit/version mutation exists and both responses represent the original result; key reuse for another body/ticket/operation returns 409.

## Known release limitation

- IDEM-002의 409/no-second-mutation 하위 조건은 통과했다. Original immutable receipt에서 actor/command ID를 조사할 수 있지만,
  rejected misuse attempt의 request ID를 별도 canonical security/access event로 남기지는 않아 durable misuse linkage는 `LIMITED`다.
  따라서 IDEM-002 gate 전체는 `LIMITED`다. 릴리스 hardening에서 새 audit 제품 기능을 추가하지 않고 이 관측 한계를 명시적으로 유지한다.

## Compatibility and migration

- OpenAPI change: M3 outline is frozen to the implemented flat command shape; no released M3 implementation exists, so this is pre-release contract alignment.
- V7 remains the additive expected-version migration. V14 adds a non-unique partial `(actor_id, command_id, created_at, id)` index for STAFF replay lookup. Non-unique is required because CreateChildTicket legitimately writes parent and child audits under one command ID; multiple lookup matches fail closed.
- V14 is forward-only and changes no row. Rollback is application rollback plus restore/forward-fix; dropping the index is not an automated supported rollback. V11→V14 operations and 1M-audit performance evidence must be regenerated.

## Human explanation

- Per-field audit history is the smallest durable fact set that supports safe stale merges without a second event-sourced state store.
- Retrying only after an optimistic write failure avoids blocking readers/writers and re-runs authorization/conflict checks on current ownership.
- The conservative write policy is replaceable because global read and ownership-scoped mutation are distinct product decisions.
