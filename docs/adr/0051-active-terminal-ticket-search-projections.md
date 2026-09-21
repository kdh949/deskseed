# ADR 0051 — Active and immutable terminal ticket search projections

Status: Accepted
Date: 2026-09-21

## Context

Deskseed currently maintains one million staff-search rows in `ticket_search_documents`. That V35
projection is transactionally refreshed for mutable ticket, comment, requester, group, and assignee
fields and remains the only runtime search read source after ADR 0050.

The canonical state machine distinguishes `SOLVED` from `CLOSED`: a solved ticket can return to
`OPEN`, while `CLOSED` has no outgoing product transition and every current product command rejects
further ticket mutation. Therefore `SOLVED` is still active search data; only `CLOSED` is terminal.
The current product exposes no ticket-delete operation, although canonical ticket deletion still has
database `ON DELETE CASCADE` projection semantics.

A read-only personal-staging inventory on 2026-09-21 found 399,142 `CLOSED` tickets and 600,858
non-closed tickets in the same one-million-ticket PostgreSQL database. The existing projection used
3,751,903,232 bytes including indexes, and its trigram index used 1,199,316,992 bytes. These figures
bound migration/storage planning only. They are not Grafana evidence that the mixed projection is the
cause of request latency.

## Decision

- Add `active_ticket_search_documents` and `terminal_ticket_search_documents` without removing or
  changing `ticket_search_documents`.
- `SOLVED` and every other non-`CLOSED` status belong to the active projection. Only `CLOSED` belongs
  to the terminal projection.
- Active ticket/search-field mutations refresh only the active projection. A `SOLVED -> CLOSED`
  transition inserts the final normalized document into the terminal projection and deletes the
  active row in the same canonical transaction.
- A terminal row cannot be updated. Requester/group/assignee label changes and even an out-of-contract
  direct comment mutation do not rewrite it. Canonical ticket deletion removes either projection row
  through the existing FK cascade.
- Both projections retain separate normalized subject, requester, group, assignee, PUBLIC-comment,
  and INTERNAL-comment fields. `rank_schema_version` versions that field boundary without choosing
  ranking weights or candidate indexes before the next measured read-path slice.
- V95 creates empty additive tables, transactional maintenance triggers, durable backfill state, a
  reconciliation function, and one bounded-batch backfill function. It does not scan or copy the
  million-row corpus inside Flyway.
- One backfill call handles 1–1,000 ticket numbers and is one transaction. A durable ticket-number
  checkpoint makes repeated calls idempotent and resumable. The batch takes an exclusive advisory
  transaction lock while product projection refreshes take its shared form, preventing a stale batch
  from overwriting a concurrent canonical refresh.
- Completion requires zero missing, unexpected, and duplicate rows from
  `reconcile_split_ticket_search_documents()`.
- The runtime search SQL continues to read `ticket_search_documents`. A later independently reviewed
  PR may switch candidate reads behind a flag after backfill and measurement gates pass.

## Consequences

- PR1 changes storage/write behavior but not the HTTP contract, authorization, ranking, result set,
  or current search read plan. It cannot claim a user-visible latency improvement.
- While both generations coexist, mutable non-closed writes maintain the old and active projections.
  Closure maintains the old row and finalizes the terminal row. This adds bounded write/WAL/storage
  cost that must be observed during backfill and normal writes.
- Existing terminal tickets are backfilled from their current canonical row and related labels. There
  is no retained historical snapshot from their original close instant; V95 does not invent one.
- Application rollback is immediate because reads still use V35. Additive V95 objects stay in place;
  dropping them or the V35 projection requires a separately approved cleanup after all dependent
  read paths are retired.
- If `CLOSED` becomes reopenable or mutable, this decision must be replaced before such a product
  contract is implemented.
- ADR 0052 activates the split read path with an explicit active-before-terminal ordering contract;
  V35 remains the rollback source.

## Verification

- PostgreSQL integration tests cover active refresh, PUBLIC/INTERNAL separation, same-transaction
  close movement, immutable terminal snapshots, deletion cascade, bounded resume, and reconciliation.
- Automation integration tests prove required audit failure rolls back `CLOSED` and terminal
  projection insertion together.
- Personal staging records Flyway/image/volume identity, batch checkpoints, reconciliation, DB
  CPU/I/O/WAL/lock, Hikari, audit failure, health, and recovery. Performance causes are stated only
  to the extent supported by saved Grafana evidence.
