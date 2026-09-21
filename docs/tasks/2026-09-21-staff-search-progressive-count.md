# Staff search progressive count vertical slice

## Goal

상담사가 `/agent/search`에서 전체 exact count를 기다리지 않고 첫 페이지를 받거나, 안전한 시간 안에 검색 범위를 좁히라는 안내를 받는다.

## Decision and source references

- Decisions: D-008, D-018, D-033, D-036, D-041, D-045, D-048, D-064, D-065, D-067
- Accepted ADRs: 0008, 0014, 0018, 0025, 0030, 0033, 0036, 0039, 0047, 0048, 0050
- Requirements: REQ-SRCH-001, REQ-PERM-001, REQ-AUD-003/004/005/008, REQ-OPS-002
- Operation: `searchAgentWorkspace`
- Gates: PERM-001, SEARCH-AUD-001/002, PERF-001/003, UI-004/005/006, DOC-001

## Actor and source

- Actor: authenticated active `STAFF`
- Source: `AGENT_UI`
- Read scope: `ALL_TICKETS` under the current server-side authorization predicate
- Interaction: first page and signed cursor pages reuse `X-Interaction-Id`; result open keeps the
  originating `searchEventId`.

## Product and UX contract

- `resultCount` is `{ value, relation }`, never an unqualified integer.
- `EXACT` is shown as an exact total; `LOWER_BOUND` is shown as “N개 이상”; `UNAVAILABLE` never
  invents a number.
- A non-numeric query shorter than three Unicode code points requires at least one narrowing filter.
- A query that exceeds the five-second SQL budget returns an actionable 422 refine-query state.
- Loading, empty, denied, broad-query, server-error, cursor paging, and result-origin navigation remain
  distinct.

## In scope

- Core OpenAPI change and manually reviewed examples/descriptions
- v2 signed cursor with cumulative returned-row count
- `limit + 1` page SQL without synchronous exact count
- transaction-local PostgreSQL statement budget
- relation-aware canonical search audit metadata and Audit Explorer projection response
- Staff Console decoding and truthful result-count/refine-query UI
- migration, integration/unit/story fixtures, traceability and performance documentation

## Out of scope

- Search result correctness or ranking changes
- asynchronous exact-count endpoint
- Elasticsearch/OpenSearch, another database, replica, or new collector
- product query/index/pool changes beyond this vertical slice

## Invariants and failure semantics

- Ticket number exact matching, stable score/ticket-number cursor ordering, snapshot binding, query
  protection, authorization, and required audit persistence remain intact.
- Successful results are returned only after `SEARCH_EXECUTED` commits in the same transaction.
- Broad-query validation and SQL-budget cancellation return no result body and commit no success audit.
- Raw search text is absent from URL, cursor, ordinary logs, metrics, traces, Problem Details, and
  routine audit projection.
- No external I/O runs in the transaction.

## Compatibility and rollback

- OpenAPI change: intentional breaking response-shape change approved by the product owner.
- v1 cursors are rejected and the UI starts a fresh search.
- V94 only adds relation metadata with `EXACT` as the existing-row default; rollback is an application
  rollback followed by a forward migration if schema cleanup is ever required.
- Backend and Staff Console images are deployed from the same commit.

## Acceptance scenarios

1. Given at most `limit` matches, when the first page is searched, then the response count is exact.
2. Given more than `limit` matches, when the first page is searched, then the response returns
   `limit` rows, a next cursor, and a lower bound of at least `limit + 1`.
3. Given the last cursor page, when it is searched, then the cumulative result count is exact.
4. Given an unfiltered one- or two-code-point non-numeric term, when submitted, then 422 asks the
   agent to add characters or a filter without echoing the term.
5. Given a statement exceeding five seconds, when PostgreSQL cancels it, then 422 is returned and the
   transaction writes no successful search audit.
6. Given audit persistence failure, when an otherwise valid search completes, then 503 returns no
   protected result.

