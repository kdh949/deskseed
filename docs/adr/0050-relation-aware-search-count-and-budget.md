# ADR 0050 — Relation-aware staff search count and bounded execution

Status: Accepted
Date: 2026-09-21

## Context

Personal-staging measurements against the single 1,000,000-row ticket search projection showed that
short, broad literal-substring searches can spend tens of seconds in the PostgreSQL PAGE statement.
Application CPU, GC, connection-pool pending/timeouts, audit persistence, and database blocking did
not explain the long tail. Keeping an exact total in the synchronous first-page response forces the
search path to finish the full match-set count even though the agent can act on the first page.

The existing response exposes one integer named `resultCount` and the Staff Console labels it as an
exact total. Returning a lower bound through that shape would silently lie to older clients.

## Decision

`searchAgentWorkspace` uses a relation-aware count object:

```text
resultCount.value: non-negative integer or null
resultCount.relation: EXACT | LOWER_BOUND | UNAVAILABLE
```

- Runtime ticket search fetches `limit + 1`, returns at most `limit`, and does not execute the exact
  count in the synchronous response path.
- A signed v2 cursor carries the number of rows returned before the next page. When another page
  exists the response count is a `LOWER_BOUND`; when no next page exists the cumulative count is
  `EXACT`.
- Old v1 search cursors fail closed. They are short-lived interaction state and the client restarts
  from the first page.
- A non-numeric query shorter than three Unicode code points with no narrowing filter returns
  `/problems/agent-search-too-broad` before database search. The response contains no raw query.
- Each PostgreSQL ticket-search statement has a transaction-local five-second statement budget.
  Budget cancellation maps to the same actionable problem and rolls the transaction back.
- Successful `SEARCH_EXECUTED` audit details store both the numeric value and its relation. Query
  ciphertext, fingerprint, result membership, actor/source/request/correlation context, and strict
  audit failure behavior remain unchanged.
- Ticket-number exact lookup, authorization, score/ticket-number stable ordering, and snapshot-bound
  cursor semantics remain unchanged.

No asynchronous exact-count endpoint is added in this slice. `UNAVAILABLE` is reserved in the
contract so a later explicitly authorized count workflow does not require another shape change.

## Consequences

- The search response shape is intentionally breaking; backend and Staff Console must be deployed
  together. A mixed version fails safely instead of presenting a lower bound as exact.
- Agents receive a first page or an actionable refine-query response within a bounded database
  statement time rather than waiting indefinitely.
- Pagination preserves exact cumulative count only when the last page is reached. Earlier pages show
  a truthful lower bound.
- Audit investigators can distinguish exact totals from lower bounds.
- Broad substring ranking still scans the matching corpus inside the time budget. This ADR does not
  claim ranking or recall improvement and does not introduce Elasticsearch/OpenSearch or another DB.

## Verification

- OpenAPI/documentation contract tests cover the new count shape and 422 problem.
- PostgreSQL integration tests cover exact first page, lower-bound first page, cumulative exact last
  page, short broad rejection, audit relation persistence, and audit failure rollback.
- Staff Console unit/story coverage distinguishes exact/lower-bound labels and the refine-query state.
- Personal-staging repeats the fixed 20-query corpus with unique run IDs and records response outcome,
  latency, CPU/GC/pool/audit/DB evidence, and recovery in Grafana.

