# Agent Workspace Search — one-million-ticket baseline and V35 projection evidence

## Scope

This document compares the original P1 PostgreSQL query with the V35
`ticket_search_documents` implementation for the same `searchAgentWorkspace`
score cursor and exact-count contract. Both use the deterministic release fixture:
1,000,000 tickets, 2,000,000 comments, 100,000 customers, and one active staff actor.
The current V35 raw plans are committed in
[`release/plans-after.txt`](release/plans-after.txt) under
`SEARCH_AGENT_WORKSPACE_EXACT_COUNT` and
`SEARCH_AGENT_WORKSPACE_SCORE_FIRST_PAGE`.

The full-scan baseline completed on 2026-08-16 with source fingerprint
`43fa746d97e20a5288dc813cffec99d684a8cd980a92d997acdc266ae58785d6`.
The V35 run completed on 2026-08-18 using PostgreSQL 17.10 and the same 2 CPU /
6 GiB container limit. Its source fingerprint is
`3960342a4a857fefafb2edaa3b49e012ca23360439f8cb390128d80c1dae4d3f` and was
verified before Docker startup, after migrations, fixture load, measurement,
access measurement, and cleanup.

## Measured query shapes

| Runtime shape | Result | Original full-scan `EXPLAIN ANALYZE` | V35 `EXPLAIN ANALYZE` | V35 warm p50 / p95 |
|---|---:|---:|---:|---:|
| Exact `resultCount` with SQL authorization and numeric/text/comment matching | 301 rows | 5,371.400 ms | 2.938 ms | 0.597 / 0.699 ms |
| First 51 score/ticket-number cursor candidates with the identical visibility and matching predicate | 51 rows | 6,374.443 ms | 2.245 ms | 2.663 / 2.883 ms |

The V35 plans use a `BitmapOr` across
`ticket_search_documents_ticket_number_idx` and
`ticket_search_documents_staff_trgm_idx`, then join the 301 candidates to the
canonical ticket rows. Both queries pass the prospective 250 ms warm-cache
database-component p95 gate. The fixture has no cache or external search
service, and this evidence does **not** claim a production HTTP SLO.

## Consistency, privacy, and storage trade-off

V35 freezes document version 1 and distinct PUBLIC/INTERNAL comment segments.
Only the generated staff document combines both. Canonical mutations refresh
the projection in their transaction under a shared advisory lock; the rebuild
uses the exclusive form. Committed update lag is therefore zero, at the cost of
synchronous write and GIN maintenance. The integration corpus covers Korean,
English, exact-ticket ranking, requester email, INTERNAL-only text, literal `%`/`_`
behavior, and the deliberate no-fuzzy-match boundary; see
[`agent-search-quality-corpus.md`](agent-search-quality-corpus.md).

At 1M rows, `ticket_search_documents` uses 521 MiB heap and 231 MiB total index
storage; the trigram index is 172 MiB. Bulk fixture load plus both search/audit
rebuilds took 223 seconds, but the fixture intentionally disables per-row
projection triggers and is not an ingestion benchmark. Online write throughput
and concurrent search load remain unmeasured limitations.

Elasticsearch/OpenSearch remains rejected for this slice. Revisit it only if a
representative quality corpus, concurrent traffic, storage growth, or the fixed
PostgreSQL latency gate demonstrates a limit, together with a dual-read,
authorization, rebuild, and outage-fallback design.

Queue performance remains independently accepted: all five exact View queries
meet the documented 50 ms database-component p95 gate in
[`release/summary.md`](release/summary.md).
