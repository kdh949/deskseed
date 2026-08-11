# Task Brief — Access and Search Audit Foundation (Stack D PR 1/2)

## Goal and contract

상담사의 의미 있는 티켓 navigation과 검색→결과 열람 경로를 canonical access ledger에 연결하고, 감사 저장이 실패한 민감 조회는 projection을 반환하지 않는다.

- Branch: `feat/09-access-search-audit`, base `feat/08-transfer-child-tickets`; automatic merge prohibited.
- Requirements: REQ-AUD-003~005, REQ-AUD-008, REQ-PERM-001, REQ-SRCH-001.
- Decisions: D-008, D-013, D-018~020, D-041, D-045. ADR 0033 supersedes ADR 0014's optional-ciphertext clause.
- Operations/routes: `POST /api/v1/agent/search`, `GET /api/v1/agent/tickets/{ticketNumber}`, `/agent/search`.
- Gates: ARCH-004, ACC-001~004, ACC-007, PERM-001, RET-001/002/004, PERF-003, SEARCH-AUD-001, UI-001/002/004/005.

## Actor, interaction, and authorization

- Actor is an authenticated active `STAFF`; source is `AGENT_UI`, auth type is `STAFF_SESSION`, and only a keyed session fingerprint is persisted.
- Launch read scope remains D-041 `ALL_TICKETS`; customer/anonymous/inactive staff are rejected server-side. Agent has no access-audit list permission.
- A semantic navigation owns a fresh UUID. Its `NAVIGATION` detail read records one deduplicated `TICKET_VIEWED`; same-interaction retry/refetch adds no view, `BACKGROUND` adds no semantic view, and a refresh/new tab creates a new interaction.
- Search submit owns a fresh interaction and one `SEARCH_EXECUTED`. The bounded returned result membership is stored as immutable child metadata. A result URL carries only the opaque search event ID; the detail request sends it as `X-Origin-Search-Event-Id`, and the server accepts it only for the same actor/session and a ticket actually returned by that search before writing `SEARCH_RESULT_OPENED` and linking the view.

## Data, privacy, and transaction boundary

- Existing `AccessAuditEvent` is extended; search detail/ciphertext child tables do not create a competing generic ledger.
- Search query is sent by CSRF-protected POST, never in URL/history. PostgreSQL receives parameterized values and returns exact authorized count plus a bounded stable page.
- Routine metadata stores a redacted query and key-versioned HMAC fingerprint. Exact original text exists only as AES-256-GCM ciphertext with event ID/purpose associated data; no plaintext column, application log, trace, cache, analytics, webhook, or ordinary export path is introduced.
- Access audit enabled without a valid active 32-byte base64 key fails startup/readiness. There is no plaintext or omitted-original fallback.
- Detail/search projection and their required audit inserts share one transaction. Audit/protection failure returns 503 without protected data. No external I/O occurs in the transaction.
- Ciphertext gets an immutable per-event expiry (30 days default/configurable). A bounded idempotent retention job deletes only expired ciphertext and writes `RETENTION_JOB_EXECUTED` to the existing admin/security ledger in the same transaction; audit failure rolls deletion back. Redacted/fingerprint/canonical events remain.

## UX and states

- Existing Deskseed `AgentShell`, `TicketTable`, `ScreenState`, and ticket workspace are reused; no duplicate shell or design-system primitive was added.
- Search implements loading, empty, generic error, denied, and explicit fail-closed audit-unavailable states. Visible controls are functional; keyboard result links support normal/new-tab navigation.
- Raw search text remains component state and is absent from the result href. Only `originSearchEventId` crosses the route boundary.

## Compatibility and rollback

- Flyway V9 is additive and forward-only. Existing customer/admin/view/ticket command contracts are unchanged; the pre-release search outline changes from GET to frozen POST to keep query text out of access logs.
- Application rollback can disable the new route/API while retaining additive tables and audit history. Do not down-migrate or delete canonical audit rows; use a reviewed forward fix after backup.
- Key rotation keeps old versioned keys until their retained ciphertext expires. Primary ciphertext deletion does not claim immediate erasure from operator backups/replicas.

## Verification evidence

- Backend: full `./gradlew test` suite; PostgreSQL search filter/count/scope, strict failure, linkage/dedupe, append-only, CORS, no-plaintext schema, log capture, crypto round-trip/tamper/AAD/rotation/missing-key, 30-day expiry and audited retention rollback.
- Performance baseline: result size is bounded to 100; comment count does not introduce N+1 and the repository executes exactly two SQL statements (count + rows). One-million-row p50/p95 and audit-on/off throughput delta are not claimed in this slice.
- Frontend: 67 Vitest tests, typecheck, ESLint, formatting, production build; search/client/workspace contract tests.
- Integrated: isolated Compose backend+frontend+PostgreSQL Playwright suite, including direct DB assertion that internal refetch keeps one view/open and browser refresh produces a new interaction (4/4 scenarios passed).
- Contract/docs: `python3 scripts/validate_documentation.py` and OpenAPI/UI route catalogs.
- Supply chain: `npm audit --audit-level=high` still reports the pre-existing two high and one moderate advisory in `react-router` and the `styled-components`→`postcss` chain. This slice adds no dependency; the app does not use SSR/RSC or untrusted CSS processing and new links are server-validated ticket numbers/UUIDs, but dependency upgrade or explicit owner risk acceptance remains a release gate.

## Explicit non-goals and owner trade-off

Audit Explorer, privileged raw-query reveal, bulk reveal, Elasticsearch, advanced search grammar, saved search/reporting, million-row benchmark, and external archive/checkpoint are not implemented. The key trade-off is intentional availability loss: if canonical audit persistence is unavailable, protected search/detail is unavailable too. PostgreSQL substring search is the measured starting point; an external search store requires later evidence and an Accepted ADR.
