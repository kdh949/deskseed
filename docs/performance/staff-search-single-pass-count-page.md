# Staff search single-pass exact COUNT and PAGE evidence

## Motivation

Deployed two-phase PAGE run `search-page-two-phase-20260920T124857Z` retained a
1,126 ms COUNT span and a 1,389 ms PAGE span for the same synthetic `common` input.
The earlier protected plan evidence showed that both statements independently read
the same 22,510 broad literal-substring candidates. The exact count and literal
substring semantics are contractual, so truncating the count or substituting token
FTS is out of scope.

## Structural change

The runtime query materializes the authorized, snapshot-bounded, filtered and scored
candidate set once. Two bounded reads of that materialized relation then compute the
exact count and select the stable cursor page. Only selected rows receive detail joins.

| Property | Before | After |
|---|---|---|
| JDBC search statements | COUNT + PAGE (2) | combined COUNT+PAGE (1) |
| base `ticket_search_documents` candidate scans | 2 per request | 1 per request |
| exact count | yes | yes |
| literal substring and score | unchanged | unchanged |
| detail joins | selected page after prior PAGE fix | selected page |
| schema/index/pool | unchanged | unchanged |

The standalone `countSql` remains available only to the protected load-plan capture
tool so historical COUNT plans stay comparable; runtime search does not execute it.

## Correctness evidence

- Focused fast/integration tests pass for score and updated order, both cursor forms,
  snapshot, exact count, status/priority/group/assignee/SLA filters, INTERNAL comment
  search, ticket-number rank, literal wildcard escaping, empty results, and required
  audit failure.
- `StaffTicketQueryEvidenceIntegrationTest` verifies one JDBC search statement and
  unchanged exact result count.
- Raw query text stays in bound parameters and is absent from generated SQL.

## Measurement boundary

Local tests prove query shape and behavior, not deployed latency. After building and
deploying a SHA-pinned image, re-run the same one-search corpus case with a unique
`TEST_RUN_ID` and compare Grafana HTTP overall, the combined PAGE span, SQL queryid,
errors/drop, pool, database waits and recovery. The prior COUNT+PAGE sum is a component
comparison only; one request is not a p95 claim.
