# ADR 0054 — Latest-first staff search order index

- Status: Accepted
- Date: 2026-09-21
- Verification record updated: 2026-10-29
- Decision: D-071
- Requirements: REQ-SRCH-001, REQ-PERF-001, REQ-PERF-002, REQ-OPS-002

## Context

After ADR 0053 was deployed to the one-million-ticket personal-staging database, the fixed
20-query smoke completed without timeout, but two of three repeats still contained a broad `topic`
request whose `PAGE` phase took about 3.5 seconds. A sampled repeat spent 3.81 seconds of its
3.84-second server trace in `deskseed.search.page`; audit took 10.95 milliseconds. The new
latest-first substring statement appeared as its own `pg_stat_statements` query ID with a
3.508-second maximum and 10,341 cumulative temporary blocks written across 45 completed calls.

The split projections already have independent `staff_document gin_trgm_ops` indexes. The page must
also order matching canonical tickets by `(updated_at desc, ticket_number desc)`, but `tickets` has no
unfiltered index that can provide that order. Existing queue indexes have leading status/priority or
ownership columns and cannot provide the unfiltered order directly.

At the initial decision stage, these observations established a bounded query-path candidate but did
not reveal the executor nodes or prove that the planner would choose the new path. The general
personal-staging policy excludes automatic `EXPLAIN ANALYZE`; the explicitly authorized, bounded
post-deployment exception on 2026-09-21 is recorded under Follow-up verification below.

## Decision

- Add `tickets_staff_search_latest_idx` on
  `(updated_at desc, ticket_number desc) include (id)` through Flyway V97.
- Keep the trigram indexes, exact-number path, literal substring predicate, authorization, active then
  terminal partition order, cursor v3, five-second statement timeout, and required audit unchanged.
- Treat the B-tree as a planner option for a bounded latest-first page, not as proof of improvement.
  Adopt the migration only after exact-SHA personal-staging repeats show a lower broad-query tail.
- Do not change PostgreSQL globals, `work_mem`, Hikari settings, or introduce another database, cache,
  or external search service in this slice.

## Consequences

- The index adds write amplification and storage for every ticket insert and `updated_at` change.
- Flyway creates the index before the new backend is accepted as healthy. The personal-staging deploy
  records migration duration, disk headroom, image revision, restart count, and post-deploy recovery.
- The index does not change response membership, ordering, cursor encoding, permissions, or audit
  semantics. No backfill or projection rewrite is required.
- If live evidence rejects the candidate, roll back the application to the preceding exact SHA and
  remove the unused index only through a later additive migration; do not edit applied V97.

## Verification

- Migration coverage checks the exact index key order and included ticket ID.
- Existing search integration, cursor, SQL-plan, permission, audit, and split-projection tests remain
  green.
- Personal staging repeats the protected fixed corpus and the sampled `topic:3` diagnostic with unique
  run IDs. k6 termination evidence remains separate from Grafana/Tempo/Prometheus and cumulative
  `pg_stat_statements` evidence.

## Follow-up verification — 2026-09-21

After V97 deployment, an explicitly authorized `EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)`
diagnostic ran on the same one-million-ticket personal-staging database at 11:52:57–11:54:18 UTC.
The deployment was `b6be5ae34b54d442f933b660e88abb1d262a8dc0`, PostgreSQL 17.11, Flyway 97. The
queries ran in read-only transactions with a five-second statement timeout, a one-second lock timeout,
and no concurrent load test. This one-time exception does not change the general operating policy.

- The post-deployment latest-first plan used `tickets_staff_search_latest_idx` through an Index Only
  Scan, followed by primary-key probes into the active or terminal search projection. Neither plan
  contained a Sort node or temporary block I/O.
- For protected `topic:3`, the active query examined 176 ordered tickets and returned 26 rows; first
  execution was 6.777 ms and the warm repeat was 4.652 ms.
- The separately forced terminal-only diagnostic examined 11,609 ordered tickets and returned 26 rows;
  first execution was 65.427 ms and the warm repeat was 33.553 ms. The application did not need the
  terminal query for this input because the active query filled the page.
- These SQL execution times are separate from the HTTP before/after measurements. The diagnostic
  confirms the deployed index path and absence of a Sort node for this input; it does not establish
  the exact pre-V97 executor plan, the earlier sort contribution, or a general latency percentile.

The execution summary and sanitized plan artifacts are recorded as E-046 in the
[search optimization handoff](../tasks/2026-09-21-postgresql-search-optimization-handoff.md#bounded-explain-analyze-결과).
This section records the 2026-09-21 diagnostic. The documentation was synchronized on 2026-10-29;
no new database diagnostic was run for this documentation update.
