# Implementation Brief — Ticket Composer and Conflict Recovery (Stack C PR 2/3)

## Goal

상담사가 Ticket Workspace에서 PUBLIC 답변 또는 INTERNAL 메모와 상태·우선순위·그룹·담당자 변경을 한 번에 저장하고, 동시 편집 충돌 뒤에도 입력을 잃지 않은 채 최신값을 확인해 선택적으로 재시도한다.

## Decision and source references

- Decision IDs: D-003, D-005, D-007, D-018, D-030, D-031, D-032, D-041, D-042, D-049
- Accepted ADRs: 0003, 0005, 0007, 0018, 0019, 0020, 0021, 0030
- Requirements: REQ-TKT-007, REQ-TKT-010, REQ-TKT-013~015, REQ-AUD-001/007, REQ-UI-001/003/005/006
- API operations: `getAgentTicket`, `updateAgentTicket`
- Screen: AGT-004
- Gates: TKT-002/003/006, CHG-001~003, IDEM-001~003, IDEM-004 response-loss path, UI-001~005

## Actor and source

- Actor: authenticated, active `STAFF` (`AGENT` or `ADMIN`).
- Source: `AGENT_UI`.
- Read scope: `ALL_TICKETS`; write remains `GROUP_OR_ASSIGNEE` and is exposed as an `UPDATE` capability only when the current actor can mutate the ticket.
- Resource constraints: group/assignee choices are server-projected active assignment options; the command still validates membership and authorization server-side.
- Request/correlation: the browser generates one `clientCommandId` per logical payload and preserves it across an ambiguous response, reload, and manual refresh. Edit, definite failure/conflict, or confirmed command success starts a new lifecycle; server request/correlation semantics remain authoritative.

## Product and UX contract

- Route: `/agent/tickets/:ticketNumber`.
- PUBLIC is the explicit default composer mode. INTERNAL has text, icon, warning surface, live announcement and ARIA tab semantics.
- Drafts are stored per staff, ticket and visibility. Field edits are stored separately from server state and only actual differences enter `changedFields`.
- One submit carries `expectedVersion`, exact `changedFields`, their values and at most the active mode's non-blank comment.
- A `409` focuses a danger banner at the top of the property panel, names the conflicting fields, fetches the latest ticket as `BACKGROUND`, and preserves both comments and all local field inputs.
- Each conflicting field requires an explicit server-value or keep-local choice. Same-field overwrite is never automatic.
- Submitting blocks duplicate save. Dirty tabs expose text/symbol state. Route and unload navigation warn while unsaved input remains.
- Errors preserve drafts and show the safe request ID.

## In scope

- Frozen Agent detail/command contract additions for assignment options, write capability and field-conflict extensions.
- Agent detail projection of active groups and active members.
- Client command decoder/encoder and exact changed-field model.
- Editable property panel, mode-separated persisted composer, combined submit and server reconciliation.
- Conflict banner, focus management, selective resolution/retry, unsaved tab/navigation feedback and request-ID errors.
- Component/API tests, two-context browser concurrency tests, customer visibility E2E, axe/keyboard/visual evidence.

## Out of scope

- Rich text, attachments, macros, transfer command UI, child creation, custom fields, tags and real-time transport.
- Automatic same-field overwrite or automatic INTERNAL-to-PUBLIC mode conversion.
- New global client state, Event Sourcing, Redis, WebSocket or external network I/O.

## Invariants and failure semantics

- PUBLIC/INTERNAL separation is enforced server-side; browser mode is not an authorization boundary.
- Ticket current row remains source of truth. Comment and field mutation plus one ordered audit commit or roll back together.
- `expectedVersion` is the latest confirmed server version; `changedFields` is derived from current server values versus local field values.
- Same-field stale changes remain unresolved until the user chooses. Disjoint changes are merged only by the tested server policy.
- A failed/conflicted command clears no draft. Ambiguous network/5xx failure and any manual refresh preserve the original command ID and request base because a read cannot identify the write outcome; edit or definite 4xx rotates it. Success durably clears the submitted visibility and confirms fields/base version before the follow-up refresh.
- No command retry occurs automatically after an ambiguous failure.
- Assignment options do not bypass the active-group/member invariant or `GROUP_OR_ASSIGNEE` authorization.

## Data and privacy

- Browser storage contains bounded plain-text drafts, editable field IDs, base version, saved time and an optional non-secret pending command UUID; never credentials, access tokens, cookies or attachment data.
- Comment bodies remain absent from ordinary logs and are not duplicated into ticket-audit payloads.
- Assignment options contain staff display names and IDs only on the audited staff detail projection.
- Existing canonical ticket-audit retention applies; this slice adds no receipt row/table or retention executor. V14 is an index-only migration.

## Threats changed

- Accidental disclosure: explicit mode text/icon/ARIA and mode-specific clearing prevent INTERNAL/PUBLIC confusion.
- Lost update/data loss: exact field sets, server versioning, explicit conflict choice and preserved drafts.
- Duplicate side effects: submit lock plus a stable logical command ID; ambiguous failures require deliberate retry with the same ID, including after reload.
- Authorization bypass: options are informational; every mutation is re-authorized and revalidated server-side.
- Secret leakage/XSS: no secret storage and plain text rendering only.

## Acceptance scenarios

- Given separate PUBLIC and INTERNAL drafts on two tickets, switching mode/ticket restores all four buffers; successful PUBLIC submit clears only that ticket's PUBLIC buffer.
- Given changed status, priority, group, assignee and a comment, one request contains the exact field set and one comment.
- Given two actors edit different fields from one version, both commits survive and both clients reconcile to the latest ticket.
- Given two actors edit the same field, the loser receives a focused red banner, keeps every draft/input, fetches latest state and cannot retry until choosing server or local value.
- Given command validation, denial, service or network failure, no local input is cleared and the safe request ID is visible when present.
- Given INTERNAL comment success, the agent projection includes it and the customer projection does not; PUBLIC success appears to both.
- Given keyboard-only operation, mode tabs, fields, banner choices, submit and navigation dialog are reachable and axe reports no serious/critical issue.

## Validation

- `cd frontend && npm run lint`
- `cd frontend && npm run typecheck`
- `cd frontend && npm test`
- `cd frontend && npm run build`
- `cd frontend && npm run test:e2e:dev`
- `cd frontend && npm run test:e2e:stack`
- `cd backend && ./gradlew test`
- Gate evidence: TKT-002/003/006, CHG-001~003, UI-001~005.

### Captured evidence (2026-08-11)

- Frontend: TypeScript, ESLint, Prettier, production build and 56 Vitest tests passed.
- Browser: all 37 development Playwright tests passed; `ticket-composer-conflict.spec.ts` covers same-field conflict, non-overlap merge and keyboard-only INTERNAL save with axe, while `ticket-command-conflict-preserves-drafts.png` captures the focused property banner and preserved PUBLIC draft.
- Full stack: isolated Compose run passed customer create plus real staff PUBLIC/INTERNAL composer saves; customer API and DOM exposed the PUBLIC reply and excluded the INTERNAL note.
- Backend: all 72 Gradle tests passed; `AgentTicketReadIntegrationTest` covers active assignment options and CLOSED ticket read-only capability against PostgreSQL.
- Contracts: `validate_documentation.py` and `verify_seed.py` passed with 43 OpenAPI paths and 53 operations.

## Compatibility and migration

- OpenAPI: compatible additive detail fields plus frozen command conflict extensions; no released M3 client is broken.
- Database migration/backfill: V14 adds only the partial `ticket_audits` staff-command lookup index; it does not add a receipt table or copy comment bodies.
- Rollback: application/UI rollback plus forward-fix or restore. Flyway has no down migration; older clients ignore the optional persisted command ID.

## Human explanation

The UI never guesses that a command succeeded and never treats a refreshed server row as permission to overwrite a conflicting local choice. Keeping server state, field form state and visibility-specific drafts separate is the smallest model that preserves user work while honoring field-aware concurrency.
