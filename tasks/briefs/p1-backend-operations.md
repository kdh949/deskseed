# P1 Backend Operations — Views, Search, Batch, Files, Exports, and Platform Limits

## Goal

상담사와 보안 감사자가 P1 운영 기능을 현재 ticket/audit 보안 경계 안에서 사용하고, 다중 인스턴스에서도 재현 가능한 PostgreSQL 근거와 API 계약을 갖게 한다.

## Decision and source references

- Decision IDs: D-003, D-005, D-008, D-013, D-014, D-018, D-019, D-020, D-026, D-030, D-031, D-032, D-036, D-037, D-041, D-042, D-045, D-047
- Accepted ADRs: 0003, 0005, 0008, 0012, 0013, 0014, 0015, 0016, 0018, 0022, 0023, 0025, 0026, 0030, 0031, 0033, 0036, 0037, 0038, 0039
- Requirements: REQ-VIEW-001, REQ-SRCH-001, REQ-BULK-001, REQ-SLA-001, REQ-INT-002, REQ-INT-005, REQ-FILE-001, REQ-AUDX-001
- Broad requirements not completed: REQ-CFG-001 (tags absent), REQ-EXP-001 (ticket detail/change-history/incremental export absent)
- Contract operations: `listAgentViews`, `createAgentSavedView`, `updateAgentSavedView`, `deleteAgentSavedView`, `previewAgentSavedView`, `reorderAgentSavedViews`, `listTicketsInView`, `searchAgentWorkspace`, `executeAgentTicketBatch`, `createAgentAttachmentUpload`, `createCustomerAttachmentUpload`, `downloadAgentAttachment`, `downloadCustomerAttachment`, `createAuditExport`, `getAuditExport`, `downloadAuditExport`
- Gates: ARCH-001/002/004, CHG-001/002, ACC-002/003/007, AUD-003/004, EXT-001~004, SLA-001/002/004/005/006/008, FILE-001/003/004/006, INT-AUTH-004, IDEM-001, CONC-001, RET-001, PERF-001/002, ANA-007

## Actor and source

- Saved views, search, and batch commands run as active `STAFF` from `AGENT_UI`. Read remains `ALL_TICKETS`; existing group-or-assignee write policy remains authoritative.
- PERSONAL views are owner-only. SHARED mutation requires `ADMIN` plus `saved-view:shared:manage`; SYSTEM definitions are read-only.
- Attachment upload/download is an active staff or ticket-scoped customer action. Customers receive only PUBLIC links; staff ticket authorization remains server-side.
- Audit export is requester-bound and requires current `audit:export` at creation, status lookup, and download. Its worker is `SYSTEM_JOB`.
- Platform limits are keyed only after authenticated `INTEGRATION_CLIENT` identity and run with source `PLATFORM_API`.

## In scope

- V30: five SYSTEM saved views plus versioned PERSONAL/SHARED definitions/order.
- V31: PostgreSQL DB-clock atomic Platform API limiter.
- V32: narrow attachment module with private storage/scanner ports, quarantine, CLEAN-only linking, cleanup, and staff/customer downloads.
- V33: private CSV/JSONL audit export artifacts, leased worker, recovery, expiration, and cleanup.
- Stable search cursor/exact count/SLA filter, bounded batch command, SLA projection consistency, and external-reference count projection.
- Core OpenAPI, traceability, frontend API fixture, browser E2E fixture, Compose contract fixture, migrations, and measured queue/search evidence.

## Out of scope

- Tags, custom fields, forms, macros, triggers, automations, webhooks, rich text, redaction, AI, ticket-content/incremental export, generic BI/export builder.
- Redis, Kafka, Elasticsearch/OpenSearch, WebFlux/R2DBC, Kubernetes, microservices, Event Sourcing, and multitenancy.
- A separate view-count/search projection: the current 1M evidence does not justify one.

## Invariants and failure semantics

- A saved view is an allowlisted, versioned condition AST, never a stored ticket ID list. Rows, preview, and counts compile the same AST with the same SQL authorization predicate. The first 20 visible views share one parameterized `UNION ALL` count round-trip.
- Search cursors bind normalized query fingerprint, filter, sort, score/ticket-number position, and snapshot. The raw query is neither URL nor ordinary-log data; protected search audit and search-to-view semantics remain intact.
- A batch has 1–100 unique explicit tickets. Each item has `expectedVersion` and a stable `clientCommandId`, runs in its own `REQUIRES_NEW` transaction through the existing command service, and returns SUCCEEDED/CONFLICT/DENIED/NOT_FOUND/VALIDATION_FAILED. PUBLIC/INTERNAL comments are not expressible by its schema.
- Ticket mutation and TicketAudit remain one transaction. Required TicketAudit or sensitive AccessAudit failure prevents the protected success response. Batch infrastructure/data-access failure is fail-closed rather than falsely reported as an item-local result.
- Attachment bytes stream to private quarantine outside a ticket transaction; checksum/MIME/scanner/store failures are fail-closed. Only CLEAN objects link during ticket creation/comment mutation. Infected, quarantined, deleted, expired, or unlinked objects never download.
- Export workers claim with `FOR UPDATE SKIP LOCKED` and a lease. Attempt-specific private artifact keys prevent a stale worker from publishing a later attempt. Owner/current capability are checked again before a no-store download; CSV formula prefixes are escaped.
- The limiter uses PostgreSQL time and atomic upsert so instances share a bucket. Database unavailability returns 503; it never bypasses quota. Existing 429 and rate-limit headers remain unchanged.

## Data and privacy

- No object key, public URL, raw object byte, raw search query, password, API key, session cookie, or export artifact URL is exposed in ordinary DTOs/logs/audits.
- View configuration/execution, protected search, attachment upload/scan/link/view/download/quarantine/delete, export request/lifecycle/download, and Platform API activity retain their appropriate audit family.
- Unlinked uploads and export artifacts expire through bounded cleanup. The local test adapters are private and deterministic; production object-store/scanner adapters remain ports rather than browser-delivered secrets.

## Acceptance scenarios

1. PERSONAL owner, SHARED administrator, and SYSTEM definition mutation follow the documented ownership/capability/version rules; view execution is access-audited.
2. A search cursor reused with a different query/filter/sort is rejected, while exact count and SLA-state filtering use SQL-side authorization.
3. A mixed batch commits only successful items, leaves failed items/audits unchanged, and replays a successful item without a second mutation or audit.
4. MIME mismatch, deterministic malware, expired, and INTERNAL attachment attempts cannot return bytes to an unauthorized caller; an audit-write failure fails closed.
5. A stale audit-export lease is reclaimed once; READY artifact download records checksum metadata/audit, formula values are escaped, and expiry prevents later bytes.
6. Two Platform limiter instances exhaust one shared database bucket and a database failure yields the documented 503.

## Validation

- PostgreSQL integration: full `backend/./gradlew test`; focused migration, architecture, runtime OpenAPI, search, auditor authorization, attachment, export, batch, external-reference, and limiter tests.
- Contract/docs: `make docs-check`.
- Frontend fixture: `npm run format:check`, `npm run lint`, `npm run typecheck`, `npm run check:design-system-boundaries`, `npm test`, `npm run build`, and `npm run test:e2e`.
- Stack: isolated-port Compose smoke and `bash scripts/run-p1-contract-e2e.sh`; the latter calls P1 APIs through the frontend proxy and checks the runtime Core OpenAPI directly from the backend documentation surface.
- Performance: `bash scripts/run-release-performance.sh --scale release --output-dir /private/tmp/deskseed-p1-release-performance-20260816`; committed evidence contains 1M tickets, 2M comments, `EXPLAIN (ANALYZE, BUFFERS)`, source fingerprint, and cleanup result.

## Compatibility and migration

- All Core API additions are additive. `TicketContext.externalReferences` is replaced by `externalReferenceCount`; the existing lazy external-reference list stays authoritative.
- V1–V29 are untouched. V30–V33 are additive and `P1AdditiveMigrationTest` proves a populated V29 schema upgrades to V33.
- There is no destructive down migration. Operational rollback is application rollback plus a forward repair/cleanup after backup; never renumber, edit checksums, or delete an applied Flyway history row.

## Human explanation

PostgreSQL remains the common source of truth for tickets, authorization-constrained conditions, export leases, and the shared rate bucket. The principal trade-off is deliberately accepting current broad-substring search cost instead of prematurely adding cache/search infrastructure: the committed 1M plan is the evidence for the next projection decision.
