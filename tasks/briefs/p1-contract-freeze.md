# P1 Contract Freeze — Saved Views, Batch, Attachments, Audit Export, and Platform Limits

## Goal

P0의 ticket/audit/security 불변식을 유지한 채, P1의 독립 vertical slice가 구현을 시작할 수 있도록 API·권한·실패·migration 초안을 먼저 동결한다.

## Decision and source references

- Decision IDs: D-003, D-005, D-008, D-013, D-014, D-018, D-019, D-020, D-026, D-030, D-031, D-032, D-036, D-037, D-041, D-042, D-045, D-047
- Accepted ADRs: 0003, 0005, 0008, 0012, 0013, 0014, 0015, 0016, 0018, 0022, 0023, 0025, 0026, 0030, 0031, 0033, 0036, 0037, 0038, 0039
- Requirements: REQ-VIEW-001, REQ-SRCH-001, REQ-BULK-001, REQ-SLA-001, REQ-INT-002, REQ-INT-005, REQ-FILE-001, REQ-AUDX-001
- Broad requirements intentionally not completed: REQ-CFG-001 (tags absent), REQ-EXP-001 (ticket/incremental export absent)
- Contract operations: `listAgentViews`, `createAgentSavedView`, `updateAgentSavedView`, `deleteAgentSavedView`, `previewAgentSavedView`, `reorderAgentSavedViews`, `listTicketsInView`, `searchAgentWorkspace`, `executeAgentTicketBatch`, `createAgentAttachmentUpload`, `createCustomerAttachmentUpload`, `downloadAgentAttachment`, `downloadCustomerAttachment`, `createAuditExport`, `getAuditExport`, `downloadAuditExport`
- Gates: ARCH-001/002/004, CHG-001/002, ACC-002/003/007, AUD-003/004, EXT-001~004, SLA-001/002/004/005/006/008, FILE-001/003/004/006, INT-AUTH-004, IDEM-001, CONC-001, RET-001, PERF-001/002, ANA-007

## Actor and source

- Saved-view/search/batch: active `STAFF`, source `AGENT_UI`; initial read is `ALL_TICKETS`, writes remain the existing group-or-assignee policy.
- SHARED view settings: `ADMIN` with explicit `saved-view:shared:manage`; PERSONAL settings: owner only. SYSTEM definitions are read-only.
- Attachment upload/download: active staff or ticket-scoped customer; customer has only PUBLIC links, staff can read PUBLIC/INTERNAL according to server-side ticket policy.
- Audit export: requester is a `STAFF` with current `audit:export`; status and download are requester-bound and re-check the current grant.
- Platform limiter: authenticated `INTEGRATION_CLIENT`, source `PLATFORM_API`; the client identity comes only from validated machine authentication.

## Product and API contract

- Saved Views are versioned, allowlisted condition AST definitions, never ticket-ID lists. The initial AST supports only status, priority, group, assignee and First Reply SLA state; it rejects tags, raw SQL, SpEL, JavaScript and scripts. Five seeded SYSTEM definitions remain available.
- View rows, preview, and counts use one compiler and the same SQL authorization predicate. Counts cover the first 20 visible definitions using one parameterized `UNION ALL` round-trip; lists remain authoritative.
- Search accepts an opaque signed cursor in the JSON body. It is bound to normalized query fingerprint, filters, sort and snapshot; score/ticketNumber ordering is available without putting the raw query in a URL or log. It returns exact count, `nextCursor`, and SLA-state filtering.
- A batch contains 1–100 unique explicit ticket numbers. Each item has `expectedVersion`, a stable `clientCommandId`, and exactly one allowlisted update or transfer command. Items are independent transactions and report `SUCCEEDED`, `CONFLICT`, `DENIED`, `NOT_FOUND`, or `VALIDATION_FAILED`; comments are not representable in the schema.
- Detail exposes `externalReferenceCount`; the lazy external-reference list operation remains the authoritative full-list API. Neither detail nor the integration backend fetches a remote URL.
- Attachments use a private object-store port and malware-scanner port. Upload is bounded streaming to quarantine, SHA-256/MIME validation, deterministic scanner decision, then CLEAN. Only CLEAN uploads can be linked within ticket create/comment transaction; non-clean, deleted, expired or unlinked-expired objects cannot download.
- Audit export creates a durable job then a worker claims it with `FOR UPDATE SKIP LOCKED` and a lease. It streams CSV/JSONL to a private artifact store, records checksum/size/row count/expiry, and returns bytes only through an owner/current-capability-checked no-store download endpoint. CSV cells beginning with formula prefixes are escaped.
- Platform rate limiting uses a PostgreSQL DB-clock atomic bucket inside `platformapi`; a database failure produces `503`, never quota bypass, while existing 429 headers remain unchanged.

## In scope

- Freeze all above operations in `api/core-api-outline-v1.yaml`, including Korean purpose, request/response examples, RFC 9457 problems, security, privacy and pagination/idempotency semantics.
- Add narrowly scoped REQ rows and preserve the broad requirement status caveats.
- Publish the migration blueprint: V30 saved-view definitions/order, V31 platform buckets, V32 attachment metadata/link/cleanup leases, V33 audit-export job/artifact lease metadata. Final numbers are allocated only after each prior migration exists; V1–V29 are never edited.
- Add one implementation brief per later vertical slice before its code starts.

## Out of scope

- Tags, custom fields, forms, macros, triggers, automations, webhooks, rich text, redaction, generic BI/export builder, ticket-content/incremental export, Redis/Kafka/OpenSearch/WebFlux/R2DBC/microservices/multitenancy.
- A dedicated count/search projection before explain evidence justifies it.

## Invariants and failure semantics

- Ticket current rows remain source of truth; TicketAudit/event ordering stays one ticket/one command transaction.
- PUBLIC/INTERNAL projection is enforced server-side. Sensitive read/audit persistence failure withholds protected success data.
- No external object-store or scanner call occurs in a ticket transaction. Stored quarantine object cleanup is compensating and audited.
- Database/scan/store failure is fail closed. Batch failure is item-local by design; every accepted replay must return its original terminal item result without a second mutation/audit.

## Data and privacy

- Do not store raw search query in cursor, ordinary log or non-protected audit metadata.
- Do not return object keys, public URLs, secrets, raw attachment bytes, protected audit content or external fetched content in ordinary DTOs.
- Export metadata keeps bounded filters, fields, reason, permission snapshot, checksum and expiry; artifacts have a short configured TTL and no public URL.

## Acceptance scenarios

1. A PERSONAL owner can edit/reorder only their definition; an ADMIN without `saved-view:shared:manage` cannot edit SHARED; SYSTEM changes are denied.
2. A signed search cursor cannot be replayed with a different query/filter/sort and a raw query never appears in URL/audit projection/log capture.
3. A 100-item batch returns mixed outcomes while a failed item neither changes the ticket nor creates a TicketAudit; exact replay does not create a second one.
4. An INFECTED, MIME-mismatched, expired or INTERNAL attachment cannot be downloaded by an unauthorized customer.
5. A recovered export worker claims a stale lease exactly once; expired artifacts return no bytes and record no successful download.
6. Two limiter instances sharing PostgreSQL exhaust one client bucket collectively; a database failure returns the documented 503.

## Validation

Before code is merged, parse/lint the committed OpenAPI and run its runtime operation-drift test. Each subsequent slice runs the listed PostgreSQL integration test plus `ApplicationModules.verify()`, affected documentation validation, security gate and performance/explain evidence. The final stack runs `./gradlew test`, additive migration validation, compose smoke, real-stack contract fixture/E2E, 1M queue/search `EXPLAIN (ANALYZE, BUFFERS)`, and reports any unavailable environment explicitly.

## Compatibility and migration

- The new endpoints and optional projection fields are additive. The legacy empty `TicketContext.externalReferences` placeholder is replaced with an explicit count only after the frontend contract fixture is updated in the corresponding slice; the lazy list operation stays supported.
- Migrations are forward-only/additive. Rollback means application rollback plus a forward repair/cleanup after backup; no down migration, migration renumbering or applied checksum edit is permitted.

## Human explanation

The P1 scope keeps authorization, audit and current ticket state in the existing modules. PostgreSQL is reused for condition compilation, stable cursors and the shared limiter because it is already the transactional source of truth. A separate search/count projection, Redis or external search store requires measured evidence rather than anticipation.
