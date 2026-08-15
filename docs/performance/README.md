# Performance evidence

Keep reproducible fixture definitions, the exact query, `EXPLAIN (ANALYZE, BUFFERS)` output, p50/p95 measurements, hypothesis, change, and before/after result here. Do not add an index or cache based only on intuition.

- [Agent Views and ticket detail read path](agent-ticket-read-query-plan.md)
- [Unified Audit Explorer one-million-row query plans](audit-explorer-1m-query-plan.md)
- [Release-scale exact Agent View queries, Audit Explorer plans, and strict access-write overhead](../evidence/release/performance/README.md)

The release harness also captures the P1 `searchAgentWorkspace` numeric score-cursor
first page and its exact-count query at the same one-million-ticket scale. It is
evidence for the PostgreSQL baseline, not justification for a cache, projection, or
external search service.

## PERF-001 acceptance boundary

The 2026-08-12 08:22 artifact established a reproducible baseline but did not have a
prospective numeric queue budget, so it is not used to pass PERF-001. Before the next
measurement, Deskseed adopts this fixed local database-component boundary:

- PostgreSQL 17, 2 CPU / 6 GiB container, deterministic 100k Customer / 1M Ticket release
  fixture, warm-cache server-side timing, and 30 samples per exact query;
- p95 at or below **50 ms** for each of the five `DefaultStaffView` queries;
- one joined SQL statement per page, `limit + 1 = 51`, no row-by-row label lookup, exact
  production predicates, and nonzero representative cardinality (at least one complete
  first page for `UNASSIGNED_MY_GROUPS` and `MY_CHILD_TASKS`);
- no over-budget variance allowance: any measured View above 50 ms fails that run.

The 50 ms ceiling is independently anchored to the repository's pre-existing Audit
Explorer local-plan target, not selected from an individual queue result. It is a local
component budget, not a production SLO; deployment hardware still requires a separate
baseline. The runner records this document's hash, enforces the threshold, and measures
all five Views. Only an artifact generated after this declaration may pass PERF-001.
