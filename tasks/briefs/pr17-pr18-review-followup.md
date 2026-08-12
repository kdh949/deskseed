# PR 17/18 review follow-up

## Goal

상담사의 검색 결과 링크와 감사 조사 화면이 key rotation, 특수문자 필터, projection rebuild, 비 UTC 날짜 및 browser history에서도 정확하고 bounded하게 동작한다.

## Decision and source references

- Decision IDs: D-005, D-013, D-014, D-018, D-019, D-020, D-032, D-045, D-048, D-051
- Accepted ADRs: 0013, 0014, 0018, 0021, 0033, 0036, 0037
- PRD/domain: docs/01 sections 6/8, docs/02 audit model, docs/19 sections 4/6/8/10
- API operations: `listAuditActivities`, `getAuditActivity`, `revealAuditSearchQuery`, `rebuildAuditProjection`
- Verification gates: ACC-003/004/005, AUD-002/003/005, EXP-001, PERF-002, UI-005

## Actor and source

- Actor type: STAFF
- Source: AGENT_UI / AUDIT_EXPLORER
- Required role/scopes: ordinary Agent for search-result open; `SECURITY_AUDITOR` and the existing audit permissions for Explorer operations
- Resource constraints: search result membership, same authenticated session, one-event reveal, at most 100 linked opens in one detail response
- Interaction/request/correlation semantics: existing interaction IDs and request/correlation IDs remain unchanged

## Product and UX contract

- Requirement IDs: REQ-AUD-002 through REQ-AUD-008, REQ-PERF-001
- Screen/route: AUD explorer `/audit/activity`
- OpenAPI operationIds: `listAuditActivities`, `getAuditActivity`, `revealAuditSearchQuery`, `rebuildAuditProjection`
- Parity: unified audit investigation with filter/date/history continuity; Deskseed visual identity unchanged
- States: existing loading/empty/error/denied/stale states remain; browser history returns to the first page of the restored filter
- Accessibility: existing native date inputs and pagination buttons retain names, focus and keyboard behavior
- Visual regression: no intentional visual-layout or baseline change

## In scope

- stable, encryption-key-independent staff-session fingerprint
- collision-free cursor filter canonicalization
- event-time actor/group snapshots and concurrency-safe projection rebuild
- O(1) projection count lookup and bounded search-open detail linkage
- reveal-specific denied self-audit
- local calendar date half-open interval and history-safe pagination
- V15 migration, OpenAPI, ADR/decision/traceability, tests and performance query evidence

## Out of scope

- public production deployment, external audit store, new search engine, bulk protected reveal, historical correction beyond the V15 one-time backfill

## Invariants and failure semantics

- canonical ledgers remain append-only and are not Event Sourcing stores
- sensitive read/reveal self-audit failure remains fail closed
- projection failure never rolls back a canonical write; rebuild is derived and retryable
- canonical writers take a shared transaction advisory lock while rebuild takes the exclusive counterpart
- cursors remain opaque, signed, versioned and filter-bound
- external I/O is not added

## Data and privacy

- session IDs and raw queries remain absent from audit rows and logs
- session fingerprint uses a dedicated 32-byte secret and fixed versioned digest format
- event-time display/group snapshots follow canonical audit retention
- linked-open detail returns bounded metadata only, never comment/query plaintext

## Threats changed

- prevents cursor filter confusion and encryption rotation denial of valid navigation
- prevents rebuild races and mutable-current-row rewriting of historical projection meaning
- prevents unbounded linked-open response materialization
- records denied reveal attempts in the reveal-specific ledger path
- prevents stale cursor reuse after browser history navigation

## Acceptance scenarios

- Given v1 search encryption and a stable session key, when active encryption changes to v2, then the same session can open an earlier result and records `SEARCH_RESULT_OPENED`.
- Given delimiter, Unicode, empty and null filter values, when a cursor is replayed under a different filter, then decoding fails.
- Given canonical audit snapshots, when staff display/ticket group later changes and projection rebuilds, then projected historical values remain unchanged.
- Given a writer concurrent with rebuild, then no duplicate projection error or missing canonical row occurs.
- Given more than 100 opens from one search, detail returns 100 and marks truncation.
- Given an authenticated staff member without reveal scope, reveal returns 403 and commits a denied reveal self-audit without raw query.
- Given a local calendar date, the request uses local midnight to next local midnight as an exclusive range.
- Given page 2 under filter B, browser Back to filter A sends no B cursor.

## Validation

- focused Kotlin unit tests and PostgreSQL/Testcontainers integration tests
- `./gradlew clean test`
- focused Vitest followed by full frontend test/typecheck/lint/format/build
- OpenAPI and documentation validator
- performance smoke/full only if the V15 projection/query change invalidates release evidence; otherwise explicitly reported not run

## Compatibility and migration

- OpenAPI: additive required `openedActivitiesTruncated` response field within the unreleased internal staff contract; `to` semantics clarified as exclusive
- V15: additive canonical snapshot/count columns, one-time current-value backfill, function replacement; no destructive rollback
- existing pre-V15 records receive the best available migration-time snapshot and remain stable after that point
- deployment requires a dedicated session-fingerprint key; local Compose has an explicit development-only value and production has no default

## Human explanation

- Separate canonical ledgers remain authoritative; the Explorer projection only copies immutable event-time facts.
- Shared writer/exclusive rebuild advisory locks are the smallest PostgreSQL-native race boundary and avoid a new queue/cache service.
- A fixed-size session fingerprint and a half-open date range remove boundary ambiguity without exposing raw session/query material.
