# Wave 1 Drafts and Presence progress

## Frozen preflight

| Item | Evidence |
|---|---|
| Baseline | Foundation F3 `4c06aca6560f7a8458992af6379c6954b1bc4dc1`, ultimately rooted at frozen `origin/main` `e4b7bedba69b8fe69f43fddb273522de3ac6fd1a` |
| Lane ownership | `feature/goal/draft-recovery` then `feature/goal/drafts-presence`; V70–V79; `api/core-api-fragments/40-collaboration.yaml`; `frontend/src/features/collaboration` |
| Decision/requirement | ADR 0040, D-050/D-053/D-055, REQ-COL-001, REQ-FND-004 |
| Actor/source | Authenticated STAFF session / `AGENT_UI`; session principal remains authoritative and expected-actor header is defense in depth only |

## D1: persisted draft recovery

### User scenario and contract

An authorized staff member writes a PUBLIC reply or INTERNAL note, leaves the workspace, and returns on the same or another browser. The owner-bound channel draft is restored after a 3-second debounce from the newer recoverable copy. `GET`, `PUT`, `DELETE`, and recoverable-list operations are committed in the owned Core fragment before the generated bundle is refreshed.

### Boundaries and invariants

- `ticket_drafts` is not a `TicketAudit`, does not mutate the Ticket row, and never creates a semantic `TICKET_VIEWED` event.
- `(owner_staff_id, ticket_id, composer_channel)` is unique. PUBLIC and INTERNAL are distinct, and another staff member receives a non-enumerating 404.
- A positive draft version updates/deletes exactly one current owner row; conflict returns the current version without silently overwriting another browser.
- CLOSED tickets permit owner recovery and explicit clear only; new/update is rejected.
- Pending attachment handles must be CLEAN, unlinked, unexpired, owned by the authenticated STAFF actor, visibility-compatible, and not bound to another ticket. A later comment command revalidates before it links anything.
- Browser storage retains only body, pending attachment IDs, versions, and timestamps in IndexedDB for seven days; attachment bytes, secrets, credentials, and audit data are excluded. Server expiry is thirty days and a leased bounded worker deletes expired rows.

### Transaction, audit, and failure semantics

- Ticket readability is authorized without emitting a ticket-detail access event; draft state is neither a Ticket change nor an access-audit event.
- Draft write/read transactions authorize the current STAFF principal and fail closed if the ticket is unreadable. The server never trusts a client-supplied owner.
- `PUT` uses the returned `draftVersion`; a `409` leaves the local copy intact and stops automatic overwrite. Network/5xx failure preserves the local seven-day copy.
- No outbound call occurs in a ticket transaction. No ticket mutation means there is no change-audit obligation for this slice.

### Verification

| Gate / command | Status |
|---|---|
| `AgentTicketDraftIntegrationTest` (owner/channel/CLOSED/conflict/no-ticket-audit/attachment ownership) | Passed |
| `JdbcTicketDraftStoreIntegrationTest` (PostgreSQL schema, optimistic write, TTL lease) | Passed |
| `frontend npm run typecheck` | Passed |
| Focused `client.test.ts` and `draftRecovery.test.ts` | Passed |
| `python3 scripts/bundle_core_openapi.py --check`, owned-fragment validator, documentation quality and deterministic artifact gate | Passed |
| `ArchitectureTest` | Passed |
| `frontend npm run lint` and production build | Passed (lint has the existing `public/mockServiceWorker.js` unused-disable warning only) |
| Storybook MCP `list-all-documentation` / story tests / preview | Not run — the `deskseed-design-proj` tools are unavailable in this environment; no undocumented design-system prop was introduced |
| Browser E2E/visual snapshots | Not run — first D1 commit adds model/API recovery; presence and conflict/recovery presentation remain for the stacked final slice |

### Non-goals and rollback

- Presence, collision WebSocket protocol, remote ticket update notifications, and user-visible multi-device conflict choice are not in D1; they remain on the second stacked branch.
- This migration is additive V70 only. Rollback is forward-fix or operational disablement of the API/client path; applied Flyway history is never edited or deleted.
- No Redis, broker, iframe, or multi-instance coordination is introduced.

## D2: presence and collision contract

- `api/core-api-fragments/40-collaboration.yaml` now owns a version 1 WebSocket protocol for authenticated staff presence, bounded client messages, safe snapshots/deltas, and after-commit stale notifications.
- The contract reserves an Origin-checked `/ws/agent/collaboration` endpoint, 4 KiB message limit, 120 messages/minute, 20-second heartbeat, and 60-second stale timeout. It deliberately excludes customer connections, comment bodies, customer data, credentials, and server-only connection IDs.
- `REQ-COL-002` is `BLUEPRINT_READY` until the security handshake, ticket authorization, in-memory single-instance boundary, after-commit publisher, extension-slot UI, and real-stack tests land.
