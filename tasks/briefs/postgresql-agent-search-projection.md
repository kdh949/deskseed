# PostgreSQL Agent Search Projection Task Brief

## Goal

상담사가 1M 티켓에서도 기존 검색 의미·권한·감사 계약을 유지하면서 PostgreSQL 전용 검색 문서로 bounded 검색을 수행한다.

## Decision and source references

- Decision IDs: D-008, D-036, D-048
- Accepted ADRs: 0008, 0018, 0025, 0030, 0033, 0036
- PRD/domain sections: `docs/03-architecture.md` §9, `docs/47-ticketing-depth-views-fields-macros-search.md` §5
- API contract operation ID: `searchAgentWorkspace`
- Verification gates: REQ-SRCH-001, REQ-PERF-001, PERM-001, SEARCH-AUD-001, SEARCH-AUD-002

## Actor and source

- Actor type: STAFF
- Source: AGENT_WORKSPACE / AGENT_UI
- Required role/scopes: active staff and the server-side `ALL_TICKETS` read scope from ADR 0030
- Resource constraints: staff-visible ticket projection only; no Admin/Audit/reveal/export authority is implied
- Interaction/request/correlation semantics: the existing required interaction ID and protected search audit remain unchanged

## Product and UX contract

- Requirement IDs: REQ-SRCH-001, REQ-PERF-001
- Screen/route: `/agent/search`
- OpenAPI operation ID: `searchAgentWorkspace`
- Loading/empty/error/denied/conflict states: unchanged; this slice changes the backend read model only
- Keyboard/focus/accessibility: unchanged

## In scope

- additive V35 staff-only versioned search document
- distinct PUBLIC and INTERNAL comment segments
- transactional trigger refresh for ticket, comment, requester, group, and assignee search fields
- exclusive/shared advisory-lock rebuild contract
- literal substring matching through `pg_trgm`, exact ticket-number preference, existing score/cursor semantics
- PostgreSQL integration, quality-corpus, query-plan, smoke, and 1M release evidence
- traceability and performance documentation

## Out of scope

- customer ticket-search endpoint or customer search projection
- tags, custom fields, and external references not yet present in the frozen search implementation
- typo/fuzzy/morphological search and advanced query syntax
- Elasticsearch/OpenSearch, Redis, Kafka, or another runtime dependency
- rendered frontend changes

## Invariants and failure semantics

- `tickets` remains the current state source of truth; the search table is rebuildable.
- PUBLIC and INTERNAL comment text remain separate source columns; only the staff document combines them.
- active-staff authorization stays in SQL and every successful search keeps required fail-closed audit persistence.
- canonical mutations and rebuilds share a transaction advisory lock so a rebuild cannot overwrite a concurrent refresh.
- projection refresh commits or rolls back with the canonical mutation; committed index lag is zero.
- exact ticket number keeps the highest score and score plus ticket number remains the stable cursor order.
- `%`, `_`, and `\` in user input are escaped as literal substring characters.

## Data and privacy

- Data copied: lower-cased subject, requester name/email, group/assignee display labels, and separated comment text.
- PII: requester email and comment content remain protected staff data in PostgreSQL.
- Retention: cascade/delete and refresh follow canonical primary-row retention; backups remain governed by the existing policy.
- Redaction/encryption: raw query handling is unchanged; the query is not logged or stored in the search document.
- Export/webhook exposure: none.

## Threats changed

- authorization bypass: SQL active-staff predicate remains in both count and page statements.
- secret leakage: literal query remains a bound parameter and ordinary logging tests remain required.
- audit bypass: existing strict search audit transaction remains fail-closed.
- concurrency/data loss: shared refresh lock and exclusive rebuild lock prevent lost projection updates.
- visibility mixing: PUBLIC/INTERNAL source columns are distinct and the table is consumed only by staff search.

## Acceptance scenarios

- Given Korean/English/comment/requester/group/assignee text, when active staff searches, then the same literal matches and stable scores are returned.
- Given an exact ticket number and another text match, when score sorting is used, then the exact ticket ranks first.
- Given `%` or `_`, when staff searches, then they are literal characters rather than SQL wildcards.
- Given an INTERNAL-only term, when staff searches, then it is found without copying it into the PUBLIC segment.
- Given a canonical searchable-field mutation, when the transaction commits, then the search document is current in the same transaction.
- Given a rebuild and concurrent mutation, when either obtains the advisory lock first, then the second observes/applies after it without losing the canonical change.
- Given a missing/failed required search audit write, when search is requested, then no protected results are returned.

## Validation

- `./gradlew test --tests dev.deskseed.ticketing.internal.StaffTicketQueryEvidenceIntegrationTest`
- `./gradlew test --tests dev.deskseed.staffaccess.internal.AgentTicketSearchIntegrationTest`
- `./gradlew test`
- `bash scripts/run-release-performance.sh --scale smoke`
- `bash scripts/run-release-performance.sh --scale release`
- `bash scripts/validate-docs.sh`

## Compatibility and migration

- OpenAPI classification: no contract change.
- Migration: additive V35; deploy migration before code. Old application code ignores the new table.
- Backfill: V35 runs one transactional rebuild; the performance fixture disables only refresh triggers during bulk load and invokes the same rebuild.
- Rollback: roll application reads back first, then use a forward migration to remove triggers/functions/table if required. Applied Flyway files are never edited.

## Human explanation

- PostgreSQL remains the only data store because measured 1M scans justify a local read model, not a new distributed system.
- The document preserves current literal substring behavior while GIN trigram lookup removes full scans.
- Synchronous refresh spends write work to guarantee zero committed lag and simpler failure semantics.
- Revisit an external search store only if the committed quality corpus, concurrency, index size, or p95 budget cannot be met.
