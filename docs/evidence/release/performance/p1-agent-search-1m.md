# P1 Agent Workspace Search — one-million-ticket evidence

## Scope

This is the PostgreSQL baseline for the P1 `searchAgentWorkspace` score cursor
and exact-count contract. It uses the same deterministic release fixture as the
queue gate: 1,000,000 tickets, 2,000,000 comments, 100,000 customers, and an
active staff actor. The raw plans are committed in
[`release/plans-after.txt`](release/plans-after.txt) under
`SEARCH_AGENT_WORKSPACE_EXACT_COUNT` and
`SEARCH_AGENT_WORKSPACE_SCORE_FIRST_PAGE`.

The run completed on 2026-08-16 using PostgreSQL 17.10 with a 2 CPU / 6 GiB
container. Its source manifest fingerprint is
`43fa746d97e20a5288dc813cffec99d684a8cd980a92d997acdc266ae58785d6`; the
fingerprint was verified before Docker startup, after migrations, fixture load,
measurement, access measurement, and cleanup.

## Measured query shapes

| Runtime shape | Result | `EXPLAIN ANALYZE` elapsed | Evidence |
|---|---:|---:|---|
| Exact `resultCount` with SQL authorization and numeric/text/comment matching | 301 rows | 5,371.400 ms | `SEARCH_AGENT_WORKSPACE_EXACT_COUNT` |
| First 51 score/ticket-number cursor candidates with the identical visibility and matching predicate | 51 rows | 6,374.443 ms | `SEARCH_AGENT_WORKSPACE_SCORE_FIRST_PAGE` |

The endpoint performs these bounded statements separately: the count uses the
same authorization/search predicate without detail projection, and the page
uses score plus `ticketNumber` as its stable order. The fixture has no cache or
external search service, and this evidence does **not** claim a production HTTP
SLO.

## Decision and follow-up boundary

The score page uses a top-N heapsort after broad substring/comment matching.
The observed full scans are concrete evidence for the staged PostgreSQL search
projection described in `docs/47-ticketing-depth-views-fields-macros-search.md`;
they are not authorization to add a cache, Elasticsearch/OpenSearch, or an
unmeasured projection in this slice. A follow-up must first freeze a versioned
staff-only search document, its transactional freshness/rebuild behavior,
corpus/relevance benchmark, and a target latency before changing the search
architecture.

Queue performance remains independently accepted: all five exact View queries
meet the documented 50 ms database-component p95 gate in
[`release/summary.md`](release/summary.md).
