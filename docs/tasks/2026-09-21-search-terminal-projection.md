# Active and terminal ticket search projection vertical slice

## Goal

상담사 검색의 후속 candidate read를 준비하기 위해 재개 가능한 온라인 백필로 mutable active와 immutable `CLOSED` 검색 문서를 분리하되 현재 검색 결과와 응답 계약은 바꾸지 않는다.

## Decision and source references

- Decisions: D-008, D-018, D-033, D-036, D-041, D-045, D-048, D-064, D-065, D-067, D-068
- Accepted ADRs: 0008, 0014, 0018, 0025, 0030, 0033, 0036, 0047, 0048, 0050, 0051
- Requirements: REQ-SRCH-001, REQ-PERM-001, REQ-AUD-003/004/005/008, REQ-OPS-002
- Operation: `searchAgentWorkspace` (read contract unchanged)
- Gates: PERM-001, SEARCH-AUD-001/002, PERF-001/003, OPS-004, DOC-001

## Actor and source

- Product writes: authenticated STAFF / AGENT_UI, AUTOMATION / AUTOMATION, existing platform and trigger actors.
- Backfill/reconciliation: operator SYSTEM using the migration-owned PostgreSQL functions; no HTTP endpoint.
- Read scope and authorization: unchanged `ALL_TICKETS` server predicate. The new tables are not an authorization token.
- Existing request/correlation/audit semantics are unchanged. A failed required audit rolls back the canonical mutation and projection trigger work.

## Product and UX contract

- `SOLVED` remains active because `SOLVED -> OPEN` is allowed. `CLOSED` alone is terminal.
- No OpenAPI, response, frontend, cursor, ranking, count, filter, or error-state change is introduced.
- Current runtime search continues to read `ticket_search_documents`.

## In scope

- Additive V95 active/terminal projections and terminal immutability guard
- Transactional active refresh and `CLOSED` finalization/delete movement
- PUBLIC/INTERNAL-separated normalized fields and versioned rank-field schema
- Empty-at-migration, resumable bounded backfill with durable checkpoint
- Missing/unexpected/duplicate reconciliation
- Operator batch script, schema/decision/traceability documentation
- PostgreSQL migration/integration tests and personal-staging deployment/backfill evidence

## Out of scope

- Switching search reads, new candidate indexes, ranking/accuracy changes, progressive continuation, or cache
- Elasticsearch/OpenSearch, Redis search/cache, another database, replica, or new collector
- Product ticket deletion endpoint or historical reconstruction of when an already-closed label last changed
- Removing the V35 projection or any production data

## Invariants and failure semantics

- Domain: only `CLOSED` is terminal; terminal documents are insert-once and update-rejected.
- Transaction: canonical write, old projection refresh, split projection refresh/movement, and required audit either commit or roll back together.
- Audit: no new semantic audit event; existing actor/source/request/correlation and fail-closed obligations stay authoritative.
- Concurrency: a bounded batch uses an exclusive advisory xact lock; product split-refresh paths use the shared form.
- Idempotency/retry: ticket-number checkpoint and upsert/do-nothing semantics make a committed batch resumable; failed transactions do not advance it.
- External I/O: none in the mutation or backfill transaction.

## Data and privacy

- The two tables retain the same staff-only normalized fields as V35, with PUBLIC and INTERNAL comment text in distinct columns.
- Raw query, query fingerprint/ciphertext, credentials, audit metadata, customer exports, and telemetry labels are not added.
- Retention follows canonical ticket/comment retention. FK cascade removes projection rows when an authorized canonical deletion occurs; backups retain existing policy.

## Threats changed

- Authorization bypass: unchanged read SQL and permission predicate; new projection is not exposed through HTTP.
- INTERNAL leakage: explicit field separation and integration assertions.
- Concurrency/data loss: backfill/write advisory lock, durable checkpoint, reconciliation counts, immutable terminal update trigger.
- Audit bypass: automation failure injection proves projection transition rollback.

## Acceptance scenarios

1. Given an active ticket, when searchable fields or PUBLIC/INTERNAL comments change, then only its active split document refreshes and visibility fields remain separate.
2. Given a solved ticket, when automation closes it, then its terminal document is finalized and its active row is removed in the same transaction.
3. Given required ticket-audit persistence failure, when automation attempts closure, then the ticket remains `SOLVED`, the active row remains, and no terminal row exists.
4. Given a terminal row, when a label/comment is changed outside the supported product contract, then the finalized terminal document is not rewritten.
5. Given canonical ticket deletion, then both split projection tables contain no row for it.
6. Given an interrupted backfill, when the operator invokes another bounded batch, then it resumes after the committed checkpoint and completion reconciles with zero gaps/duplicates.
7. Given application rollback, current search reads continue from the unchanged V35 projection.

## Validation

- `cd backend && ./gradlew test --tests 'dev.deskseed.ticketing.internal.SplitTicketSearchProjectionMigrationTest' --tests 'dev.deskseed.ticketing.internal.StaffTicketQueryEvidenceIntegrationTest' --tests 'dev.deskseed.automation.internal.AutomationExecutionIntegrationTest'`
- `make docs-check`
- `git diff --check`
- Personal staging: exact-SHA image/health/Flyway/volume proof; repeated bounded batches; reconciliation; Grafana OPS-004/PERF-001/003 evidence and recovery.

## Compatibility and migration

- OpenAPI classification: no change.
- V95 is forward-only and additive. It creates no million-row data inside Flyway.
- Rollback: deploy the prior application SHA; reads already use V35. Leave V95 objects/data intact.
- Cleanup/drop: prohibited in this slice and requires explicit later approval.

## Human explanation

- Separating immutable `CLOSED` documents prevents future active-field refresh work from requiring one mixed physical corpus, while keeping reopenable `SOLVED` correct.
- PR1 deliberately proves lifecycle and migration safety before selecting candidate K, indexes, or changing the user-visible read path.
- Grafana evidence of DB candidate cost, write overhead, and recovery—not table size or temporal correlation alone—decides whether PR2 should use the split projections.
