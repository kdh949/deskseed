# Agent Views and ticket detail query evidence

## Scope and decision

This evidence covers REQ-PERM-001 and PERF-001 for Stack B PR 2/3. PostgreSQL remains the source of truth; no cache, search engine, denormalized read model, or event-sourced projection was introduced.

## Query strategy

- A View page executes one bounded SQL statement and fetches `limit + 1` rows to derive an opaque next cursor.
- The fixed ordering is `tickets.updated_at DESC, tickets.ticket_number DESC`. The cursor predicate is the matching row comparison `(updated_at, ticket_number) < (?, ?)` and the versioned HMAC-signed payload is bound to the View and filter fingerprint.
- Requester, group, and assignee labels are joined into the page query. No row-level follow-up query is performed.
- Ticket detail executes three SQL statements: ticket/customer/ownership, all ordered comments, and ticket-local ordered history. Comment count does not affect statement count.
- Every successful detail read adds one mandatory `API_RESOURCE_READ` statement in the same transaction. `NAVIGATION` adds a separate `TICKET_VIEWED` statement; its unique semantic key makes a same-interaction refetch a no-op. Thus the detail projection remains three bounded reads, while a successful `BACKGROUND` request executes four statements and a `NAVIGATION` request executes five.

## Reproducible fixture and assertions

`StaffTicketQueryEvidenceIntegrationTest` creates one active staff account, one group, one customer, 40 tickets, and 100 comments on the selected ticket in PostgreSQL 17 Testcontainers.

The automated evidence asserts:

- 21-row View page: exactly 1 prepared query.
- Detail with 100 comments: exactly 3 prepared queries.
- With sequential scans disabled for deterministic plan inspection, the `my-open` cursor query plan contains `tickets_assignee_status_cursor_idx`.
- The canonical access audit table rejects runtime `UPDATE` and `DELETE`.

The relevant additive indexes are:

```sql
create index tickets_assignee_status_cursor_idx
    on tickets (assignee_id, status, updated_at desc, ticket_number desc);

create index tickets_group_status_cursor_idx
    on tickets (group_id, status, updated_at desc, ticket_number desc);

create index tickets_status_cursor_idx
    on tickets (status, updated_at desc, ticket_number desc);
```

The focused integration test deliberately checks plan shape rather than machine-specific
timing. For the release harness, the repository now prospectively declares a 50 ms
warm-cache p95 ceiling on the documented PostgreSQL 17, 2 CPU / 6 GiB, 1M-ticket profile.
All five exact `DefaultStaffView` queries must also remain one bounded joined statement,
return at most the 51-row probe page, avoid row-by-row label lookups, and have
representative fixture cardinality. There is no variance waiver for an over-budget View.
This is a local database-component budget, not a production SLO; see the
[PERF-001 release assessment](../evidence/release/performance/README.md#perf-001-gate-assessment).

## Verification command

```text
./gradlew test --tests '*StaffTicketQueryEvidenceIntegrationTest'
```
