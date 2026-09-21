# ADR 0052 — Active-first staff search reads

Status: Accepted
Date: 2026-09-21

## Context

ADR 0050 removed exact count from the synchronous first-page path and bounded each PostgreSQL
statement, reducing the measured personal-staging 1 VU p95 from 22.056 seconds to 1.601 seconds.
The remaining broad-query cost is still the candidate PAGE statement: the V35 read source mixes one
million mutable and immutable rows and must rank matches before applying the page limit. ADR 0051
therefore added and reconciled 600,858 non-`CLOSED` and 399,142 immutable `CLOSED` documents while
leaving runtime reads unchanged.

The product owner authorizes a result-order contract change so the common active-work search can stop
without ranking the terminal corpus. Accuracy evaluation is out of scope; the ordering rule must be
explicit rather than presented as globally score-ranked results.

## Decision

- With no status filter, search reads the active projection first and the terminal projection only
  when the requested `limit + 1` lookahead is not satisfied by active matches.
- `status=CLOSED` reads only the terminal projection. Any other explicit status reads only the active
  projection.
- Active results always precede terminal results. Within each partition the selected existing order
  remains `score:desc,ticketNumber:desc` or `updatedAt:desc,ticketNumber:desc`.
- A signed v3 cursor binds the partition in addition to query, filters, sort, snapshot, last tuple,
  and returned-row count. Older v2 cursors fail closed and the Staff Console restarts from page one.
- V96 adds independent GIN trigram indexes to both split projections. Personal staging may create the
  identical indexes with `CREATE INDEX CONCURRENTLY` before application deployment; the idempotent
  Flyway statements then record the schema version without rebuilding them.
- Each partition query retains the five-second PostgreSQL statement budget and runs in the same
  read-and-required-audit transaction. Authorization, PUBLIC/INTERNAL separation, query protection,
  exact ticket-number lookup, relation-aware count, and strict audit failure semantics are unchanged.
- V35 remains in place as the application rollback source. This slice does not add another database,
  replica, cache, Elasticsearch/OpenSearch, or an asynchronous exact-count endpoint.

## Consequences

- A first page containing enough active matches never reads or ranks the terminal projection. Explicit
  status filters also avoid the unrelated partition.
- Unfiltered global score order changes intentionally: a lower-scoring active match precedes every
  terminal match. Consumers must not describe the unfiltered list as one global score ranking.
- A boundary page may execute one statement per partition. A request that exhausts the active
  partition before reading terminal data can therefore spend up to one statement budget in each;
  metrics and traces must keep the partition statements distinguishable before tightening a total
  request budget.
- The split reduces candidate width but cannot guarantee every broad active-only search is fast.
  Personal-staging before/after measurements decide the observed improvement; table sizes alone do
  not prove causality.
- Deploying the previous application SHA restores V35 reads. V95/V96 tables and indexes remain
  additive until a separately approved cleanup.

## Verification

- Fast tests cover partition-bearing cursor round-trip and SQL table selection.
- PostgreSQL integration tests cover active-before-terminal pagination, explicit partition filters,
  at-most-one statement per partition, V96 indexes, and unchanged audit rollback behavior.
- Personal staging records exact image SHA, V96 state, single-DB index creation, fixed-input response
  latency/outcome, and Grafana application/DB/pool/audit/recovery evidence with absolute UTC windows.

