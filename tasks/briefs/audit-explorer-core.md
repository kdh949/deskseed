# Unified Audit Explorer Core — Stack D PR 2/2

## Goal

`SECURITY_AUDITOR`가 티켓을 하나씩 열지 않고 Ticket Change, Access/Search,
Admin/Security 원장을 한 화면에서 필터링하고, 구조화된 변경 전후와 검색→결과
열람 경로를 조사한다.

## Decision and source references

- Decision IDs: D-005, D-008, D-013, D-014, D-018, D-019, D-020,
  D-026, D-036, D-045
- Accepted ADRs: 0005, 0013, 0014, 0018, 0025, 0033
- Requirements: REQ-AUD-002, REQ-AUD-004, REQ-AUD-005, REQ-AUD-006,
  REQ-AUD-008, REQ-PERF-001, REQ-UI-005
- Screen: AUD-001; export status is an AUD-002 skeleton
- OpenAPI operations: `listAuditActivities`, `getAuditActivity`,
  `revealAuditSearchQuery`, `createAuditExport`, `getAuditExport`,
  `rebuildAuditActivityProjection`
- Gates: CHG-005, AUD-001..006, SEARCH-AUD-002, PERF-002, UI-002,
  UI-004, UI-005

## Actor and source

- Actor type: STAFF
- Role: `SECURITY_AUDITOR`
- Source: `AUDIT_EXPLORER` at the product/API boundary; persisted request source uses
  the accepted `ADMIN_UI` vocabulary until a source-enum migration is justified.
- Authorities:
  - `audit:activity:read`
  - ledger-specific read authorities
  - `audit:search-query:reveal`
  - `audit:export`
  - `audit:projection:rebuild`
- `SECURITY_AUDITOR` never receives Agent Workspace or Admin mutation authority.
- Every explorer request carries an interaction UUID plus accepted request/correlation
  IDs. Cursor paging is bound to normalized filters and a first-page snapshot tuple.

## Product and UX contract

- Route: `/audit/activity`; direct URL is protected by both frontend and backend.
- List filters are URL state except the opaque cursor.
- Default seven-day result set; stable order is `occurredAt DESC, activityId DESC`.
- Detail opens as a keyboard-accessible right drawer and restores focus to the row.
- List/detail never contains comment body or decrypted raw query.
- Reveal accepts one search event ID and a non-empty reason, verifies a recent-auth/MFA
  policy seam, returns `Cache-Control: no-store`, and self-audits every terminal result.
- Loading, empty, denied, error, unavailable-protected-content and export-request states
  are explicit. Stale projection status is visible.
- Required visual fixtures: AUD explorer list/detail/reveal at 1280, 1440 and 1920.

## In scope

- Additive Flyway migration for `SECURITY_AUDITOR`, a rebuildable normalized
  `audit_activity_projection`, projection health/checkpoint state, and export
  job/artifact skeleton tables.
- Failure-isolated canonical-insert projection hooks plus deterministic rebuild.
- Cursor pagination and date/ledger/action/actor/ticket/group/field/source/outcome/
  request/correlation/search-fingerprint filters.
- Read/detail/reveal/export/rebuild services, HTTP endpoints, permission checks and
  self-audit events.
- Search query one-event decryption via the existing versioned AEAD service.
- Deskseed Audit Explorer UI connected to the frozen OpenAPI contract.
- PostgreSQL integration, security, projection equality, performance, browser,
  visual and accessibility evidence.

## Out of scope

- Arbitrary SQL/report builder, SIEM, WORM/hash checkpoint and bulk decryption.
- Protected comment-body reveal; the list/detail shape leaves an explicit future seam.
- Long-running export worker, object creation, download URL and file generation. The
  request job, permission snapshot and `NOT_CREATED` artifact placeholder are real;
  completion/download are follow-up work.
- Elasticsearch/OpenSearch, warehouse or external projection store.

## Invariants and failure semantics

- Canonical ticket/access/admin-security rows are never updated or deleted by the
  projection or rebuild path.
- Projection insert failure cannot roll back a canonical business/access event; it
  marks projection state degraded and rebuild remains possible.
- Explorer list/detail/reveal/export self-audit must commit before protected success;
  audit persistence failure returns a stable 503 with no response data.
- A cursor is HMAC-signed, filter-bound and snapshot-bound. New self-audit rows created
  by later pages cannot cause duplicate/omitted rows inside the snapshot.
- Reveal decrypts exactly one `SEARCH_EXECUTED` ciphertext. Expiry/key retirement is a
  structured unavailable result. Authentication/tag failure returns no plaintext and
  records a failed security event.
- Export request and its audit event commit atomically. No artifact is downloadable in
  this slice.
- No external I/O occurs in a transaction.

## Data and privacy

- Projection stores only allowlisted metadata and structured scalar/reference diffs.
- Access metadata may include bounded actor/session fingerprint/IP/user-agent/auth type.
- Search list/detail stores redacted query, keyed fingerprint, filters and count only.
- Decrypted raw query exists only in the single response path, is `no-store`, and is
  never logged, cached, traced, projected, exported or sent to webhooks.
- Ciphertext retention remains the configured 30-day default. Missing ciphertext and
  retired keys do not change canonical metadata.
- Export reason, filters, fields and permission snapshot are bounded metadata; no
  protected content is selected in this skeleton.

## Threats changed

- Elevation: Agent/Admin direct audit URLs and auditor ticket/admin mutations are
  denied server-side.
- IDOR: activity and export IDs are authorized; export status is requester-bound.
- Disclosure: default DTO allowlists omit protected bodies/raw queries.
- Tampering: cursor HMAC and AEAD associated data reject modification.
- Repudiation: view/detail/reveal/export/rebuild create canonical self-audit events.
- Availability: canonical writes survive projection hook failure; protected Explorer
  responses fail closed if required self-audit cannot persist.
- DoS: date range, string lengths, page limit and result projection are bounded.

## Acceptance scenarios

1. Given ticket/access/admin histories, when an auditor applies every supported filter,
   then only matching rows appear and before/after is visible in detail without opening
   the ticket.
2. Given more than one page, when new self-audit rows are inserted between pages, then
   the signed cursor returns no duplicate or omission from the first-page snapshot.
3. Given a search event and result-open events, when detail opens, then redacted query,
   fingerprint, filters/count and origin/open linkage are visible; raw query is absent.
4. Given reveal authority, recent authentication and a reason, when one search event is
   revealed, then exact plaintext is returned with `no-store` and a reveal self-audit.
5. Given blank reason, missing authority, stale authentication, tampered ciphertext,
   expired ciphertext or retired key, then no plaintext is returned and a safe terminal
   result/problem is produced.
6. Given an auditor, when ticket or Admin mutation URLs are invoked directly, then 403
   is returned and no mutation/audit side effect for the forbidden command exists.
7. Given projection rows are removed, when rebuild runs, then source counts and sampled
   normalized records match all canonical ledgers.
8. Given one million projection rows, actor+date, ticket+date, action+date and first-page
   `EXPLAIN (ANALYZE, BUFFERS)` plans use the documented indexes and meet the recorded
   local budget.
9. Given an export request, when authority and reason are valid, then a requester-bound
   job plus `NOT_CREATED` artifact placeholder and self-audit are committed; no download
   is exposed.

## Commands and validation

```text
Backend:  cd backend && ./gradlew test
Frontend: cd frontend && npm run typecheck && npm run lint && npm test && npm run build
Browser:  cd frontend && npm run test:e2e:dev
Stack:    integrated audit explorer stack script added by this slice
OpenAPI:  existing repository lint/parse command or YAML parser test
Perf:     PostgreSQL fixture script + EXPLAIN evidence under docs/performance
```

## Compatibility and migration

- OpenAPI change: additive R2 endpoints/fields plus replacement of the unfrozen generic
  reveal outline with the frozen search-query-specific contract.
- Migration is forward-only and additive. Rollback disables the route and restores from
  backup/forward-fix; canonical ledgers remain intact if projection tables are dropped.
- Rebuild is the backfill path for all existing canonical rows.
- Existing Agent/Admin clients remain compatible; current role enum gains one value and
  generated/manual decoders must accept it.

## Human explanation

- Separate canonical ledgers retain their transaction, retention and meaning; the
  projection earns its existence through cross-ledger filter/index needs and is safe to
  rebuild.
- PostgreSQL is sufficient until measured PERF-002 evidence fails the budget.
- Reveal is deliberately non-bulk and fail-closed because raw search text is a higher
  sensitivity class than routine audit metadata.
- Export stops at a durable, permissioned request skeleton so this PR does not pretend
  a secure long-term artifact pipeline exists.
