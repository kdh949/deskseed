# Active-first split candidate search vertical slice

## Goal

상담사가 일반 검색의 첫 페이지를 요청할 때 active 후보를 먼저 반환하고 필요한 경우에만 immutable `CLOSED` 후보를 조회해 동기 검색 비용을 줄인다.

## Decision and source references

- Decisions: D-008, D-018, D-033, D-036, D-041, D-045, D-048, D-064, D-065, D-067, D-068, D-069
- Accepted ADRs: 0014, 0025, 0030, 0033, 0036, 0047, 0048, 0050, 0051, 0052
- Requirements: REQ-SRCH-001, REQ-PERM-001, REQ-AUD-003/004/005/008, REQ-PERF-001, REQ-OPS-002
- Operation: `searchAgentWorkspace`
- Gates: PERM-001, SEARCH-AUD-001/002, PERF-001/003, OPS-004, DOC-001

## Actor and source

- Actor: authenticated STAFF using AGENT_UI; deployment/index preparation uses operator SYSTEM.
- Authorization: existing `ALL_TICKETS` server predicate; a projection row never grants access.
- Interaction/request/correlation and fail-closed required audit semantics remain unchanged.

## Product and UX contract

- With no status filter, active results precede terminal results; the selected sort applies inside each partition.
- `status=CLOSED` reads terminal only; every other explicit status reads active only.
- Signed cursor v3 carries the partition. Old cursors fail closed and the existing client restarts from page one.
- `resultCount` remains relation-aware `EXACT | LOWER_BOUND | UNAVAILABLE`; no exact total is added to the synchronous path.
- Loading, empty, refine-query, denied, and error states are unchanged. This slice has no rendered UI change.

## In scope

- Runtime candidate reads from V95 split projections
- Active-first cross-partition pagination and partition-bound signed cursor
- V96 independent trigram indexes and protected plan-capture partition metadata
- OpenAPI, ADR, schema, decision, traceability, migration, integration, and personal-staging evidence
- Grafana Host CPU panel vector-matching correction discovered during the V95 backfill

## Out of scope

- Accuracy/relevance evaluation, new ranking weights, exact-count endpoint, another DB/replica, cache, or external search store
- Removing V35, changing Hikari/pool settings, or adding collectors/application telemetry
- High-load escalation while generator resource telemetry and slow-request traces remain unavailable

## Invariants and failure semantics

- Only `CLOSED` is terminal; `SOLVED` remains active and reopenable.
- Search read and required `SEARCH_EXECUTED` audit remain one transaction; audit failure returns no success.
- Each partition statement has a transaction-local five-second timeout. A timeout produces the existing refine-query problem.
- A cursor is query/filter/sort/snapshot/partition-bound and tampering fails closed.
- No external I/O occurs inside the transaction.

## Data and privacy

- Existing staff-only normalized PUBLIC/INTERNAL-separated documents are read; no new customer data is copied.
- Raw query stays out of URL, cursor, ordinary logs, metrics, and shared evidence.
- Query ciphertext/fingerprint retention and protected reveal policy are unchanged.

## Threats changed

- Authorization bypass: unchanged canonical SQL predicate is applied in both partition statements.
- Replay/tampering: signed cursor v3 binds partition and request semantics.
- INTERNAL leakage: existing separated fields and staff-only surface remain.
- Audit bypass: strict access-audit transaction behavior remains and is regression-tested.
- Deployment lock: personal staging builds identical GIN indexes concurrently before V96 records them.

## Acceptance scenarios

1. Given active and terminal matches, when an unfiltered first page is searched, then active matches are returned first and the cursor resumes at the correct partition.
2. Given enough active matches for `limit + 1`, when the first page is searched, then no terminal candidate statement runs.
3. Given `status=CLOSED`, when searched, then only the terminal projection is read.
4. Given a non-terminal status, when searched, then only the active projection is read.
5. Given a v2 or tampered cursor, when reused, then the request fails closed without exposing the query.
6. Given required audit persistence failure, when search executes, then no successful response is returned.

## Validation

- `cd backend && ./gradlew test --tests 'dev.deskseed.staffaccess.internal.AgentTicketSearchCursorCodecTest' --tests 'dev.deskseed.staffaccess.internal.AgentTicketSearchIntegrationTest' --tests 'dev.deskseed.ticketing.internal.StaffTicketSearchSqlPlanTest' --tests 'dev.deskseed.ticketing.internal.StaffTicketQueryEvidenceIntegrationTest' --tests 'dev.deskseed.ticketing.internal.SplitTicketSearchProjectionMigrationTest'`
- `make docs-check`
- `git diff --check`
- Personal staging: existing single DB only; concurrent index creation, exact-SHA deployment, V96/health/restart proof, fixed-input before/after low-load comparison, Grafana evidence and recovery.

## Compatibility and migration

- OpenAPI change: documented ordering and opaque cursor-version behavior; response shape is unchanged.
- V96 is forward-only and additive. Roll back the application SHA to restore V35 reads; retain split indexes/tables.
- The current Staff Console already restarts from page one when an opaque cursor is rejected.

## Human explanation

- The contract trades one global active-plus-terminal ranking for a smaller common active-work candidate set.
- PostgreSQL remains sufficient until measured evidence, not table size, supports a different search store.
- Grafana-visible request, DB, pool, audit, and recovery signals bound the performance conclusion.

