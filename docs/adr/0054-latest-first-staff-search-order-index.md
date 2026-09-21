# ADR 0054 — Latest-first staff search order index

- Status: Accepted
- Date: 2026-09-21
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

These observations establish a bounded query-path candidate. They do not reveal the executor node or
prove that the planner will choose the new path; personal staging does not run `EXPLAIN ANALYZE`.

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

