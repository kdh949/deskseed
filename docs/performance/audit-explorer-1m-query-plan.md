# Unified Audit Explorer 1M query-plan evidence

## Scope and target

This evidence covers `REQ-AUD-002`, `REQ-AUD-006`, `REQ-PERF-001`, and gate `PERF-002` for Stack D PR 2/2. The Explorer reads the PostgreSQL `audit_activity_projection`; the three canonical ledgers remain unchanged and independently authoritative.

The release target for a warm 1,000,000-row projection is:

- the first cursor page and actor/ticket/action + date pages use bounded index scans, never a sequential scan;
- each measured `EXPLAIN (ANALYZE, BUFFERS)` execution stays below 50 ms on the documented local container;
- the API reads `limit + 1` rows (51 for the default 50-row page), with a stable `(occurred_at DESC, id DESC)` order;
- production p95 must be re-measured on deployment hardware before changing the 50 ms local-plan target into an SLO.

## Reproduction environment and fixture

- Measured: 2026-08-12 (Asia/Seoul)
- PostgreSQL: 17 Alpine container (`postgres:17-alpine`, server 17.10 in integrated E2E)
- Host: local Docker Desktop; no explicit CPU or memory limit
- Rows: exactly 1,000,000 regular-table projection rows over 30 days
- Cardinality: 1,000 actors, 100,000 ticket numbers, 100 groups, 10 evenly distributed actions, three ledgers
- Preparation: all V11 projection indexes are created, rows are inserted, then `VACUUM (ANALYZE)` runs
- Exact fixture and queries: `scripts/audit-explorer-performance.sql`
- Runner: `bash scripts/run-audit-explorer-performance.sh`

The one-million-row insert took 39.595 s and `VACUUM (ANALYZE)` took 1.722 s. Sizes were:

| Object | Size |
|---|---:|
| Projection heap | 432 MB |
| All projection indexes | 872 MB |
| Total | 1,303 MB |

The index set costs about 2.0× the heap size. That is the explicit trade-off for the complete actor/ticket/action/group/field/source/outcome/request/correlation/fingerprint filter surface. Index usage and size must be rechecked against production filter frequency; no external search store or cache is justified by this baseline.

## Results

| Query | Chosen index | Rows returned | Buffers hit/read | Planning | Execution |
|---|---|---:|---:|---:|---:|
| First cursor page + 7 days | `audit_activity_projection_cursor_idx` | 51 | 3 / 3 | 0.903 ms | 0.083 ms |
| Actor + 7 days | `audit_activity_projection_actor_cursor_idx` | 51 | 4 / 51 | 0.648 ms | 1.372 ms |
| Ticket + 7 days | `audit_activity_projection_ticket_cursor_idx` | 7 | 4 / 9 | 0.135 ms | 0.346 ms |
| Action + 7 days | `audit_activity_projection_cursor_idx` | 51 | 8 / 27 | 0.248 ms | 0.577 ms |

All four plans met the local target with no sequential scan. The evenly distributed action fixture caused PostgreSQL to prefer the global cursor index and filter 459 rows to return the top 51; this is cheaper for the bounded top-N query than walking the action index in this distribution. The dedicated action index remains available for more selective/skewed action values, but its production value must be validated before accepting its long-term storage cost.

Representative plan excerpts:

```text
FIRST_CURSOR_PAGE
Limit (actual time=0.021..0.052 rows=51 loops=1)
  -> Index Scan using audit_activity_projection_cursor_idx
     (actual time=0.020..0.049 rows=51 loops=1)
Planning Time: 0.903 ms
Execution Time: 0.083 ms

ACTOR_AND_DATE
Limit (actual time=0.019..1.355 rows=51 loops=1)
  -> Index Scan using audit_activity_projection_actor_cursor_idx
     (actual time=0.018..1.348 rows=51 loops=1)
Planning Time: 0.648 ms
Execution Time: 1.372 ms

TICKET_AND_DATE
Limit (actual time=0.148..0.335 rows=7 loops=1)
  -> Index Scan using audit_activity_projection_ticket_cursor_idx
     (actual time=0.147..0.334 rows=7 loops=1)
Planning Time: 0.135 ms
Execution Time: 0.346 ms

ACTION_AND_DATE
Limit (actual time=0.019..0.563 rows=51 loops=1)
  -> Index Scan using audit_activity_projection_cursor_idx
     (actual time=0.018..0.558 rows=51 loops=1)
     Filter: action = 'SEARCH_EXECUTED'
     Rows Removed by Filter: 459
Planning Time: 0.248 ms
Execution Time: 0.577 ms
```

These are single `EXPLAIN ANALYZE` measurements, not p50/p95 claims. The reproducible plan shape, row bound, buffer footprint, and storage cost are the evidence for this slice.
