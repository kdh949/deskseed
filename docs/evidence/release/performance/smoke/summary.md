# smoke release performance evidence

- Result: `PASS`
- Command: `bash scripts/run-release-performance.sh --scale smoke`
- Fixture seed: `424242`; base time: `2026-08-12T00:00:00Z`
- PostgreSQL: `17.10`; container limit: `2 CPU / 2g memory`

## Verified counts

| Entity | Actual | Expected | Match |
|---|---:|---:|:---:|
| `access_audit_events` | 5000 | 5000 | t |
| `admin_security_audit_events` | 1000 | 1000 | t |
| `audit_activity_projection` | 16000 | 16000 | t |
| `customers` | 1000 | 1000 | t |
| `dangling_comment_ticket_refs` | 0 | 0 | t |
| `dangling_ticket_audit_refs` | 0 | 0 | t |
| `search_audit_details` | 500 | 500 | t |
| `ticket_audit_events` | 10000 | 10000 | t |
| `ticket_audits` | 10000 | 10000 | t |
| `ticket_comments` | 20000 | 20000 | t |
| `ticket_relations` | 100 | 100 | t |
| `tickets` | 10000 | 10000 | t |

## Production queue cardinality

| Query | Eligible rows | Returned first-page rows | Representative |
|---|---:|---:|:---:|
| `queue_my_child_tasks_first_page` | 80 | 51 | t |
| `queue_my_open_first_page` | 40 | 40 | t |
| `queue_pending_first_page` | 2000 | 51 | t |
| `queue_recently_solved_first_page` | 40 | 40 | t |
| `queue_unassigned_my_groups_first_page` | 80 | 51 | t |

## PERF-001 local queue latency budget

The fixed acceptance boundary declared before this run is warm-cache database-component p95 <= `50 ms` for every exact DefaultStaffView query on the recorded 2-CPU / 6-GiB release container profile, with one bounded joined SQL statement, a 51-row first page, and representative fixture cardinality.

| Query | After p95 (ms) | Budget (ms) | Pass |
|---|---:|---:|:---:|
| `queue_my_child_tasks_first_page` | 1.068 | 50 | t |
| `queue_my_open_first_page` | 0.746 | 50 | t |
| `queue_pending_first_page` | 0.903 | 50 | t |
| `queue_recently_solved_first_page` | 1.110 | 50 | t |
| `queue_unassigned_my_groups_first_page` | 1.221 | 50 | t |

## Warm-cache server-side latency

| Query | Before p50 (ms) | Before p95 (ms) | After p50 (ms) | After p95 (ms) | p95 change |
|---|---:|---:|---:|---:|---:|
| `audit_action_and_date` | 0.073 | 0.081 | 0.073 | 0.087 | +7.4% |
| `audit_actor_and_date` | 0.676 | 0.874 | 0.085 | 0.098 | -88.8% |
| `audit_first_cursor_page` | 0.072 | 0.084 | 0.076 | 0.095 | +13.1% |
| `audit_ticket_and_date` | 0.071 | 0.080 | 0.068 | 0.078 | -2.5% |
| `queue_my_child_tasks_first_page` | 0.616 | 0.767 | 0.601 | 1.068 | +39.2% |
| `queue_my_open_first_page` | 0.739 | 0.944 | 0.575 | 0.746 | -21.0% |
| `queue_pending_first_page` | 0.696 | 0.804 | 0.684 | 0.903 | +12.3% |
| `queue_recently_solved_first_page` | 0.667 | 0.741 | 0.701 | 1.110 | +49.8% |
| `queue_unassigned_my_groups_first_page` | 0.974 | 1.130 | 1.039 | 1.221 | +8.1% |
| `staff_command_replay_lookup` | 0.120 | 0.143 | 0.125 | 0.146 | +2.1% |

All five queue rows are exact current `StaffTicketQueryRepository.list` shapes with empty optional filters, `cursor = null`, and the API default `limit + 1 = 51`. The measured `DefaultStaffView` cases are `MY_OPEN`, `UNASSIGNED_MY_GROUPS`, `PENDING`, `RECENTLY_SOLVED`, and `MY_CHILD_TASKS`; there is no `PENDING_OR_ON_HOLD` or `RECENTLY_UPDATED` view. No synthetic queue control query is mixed into this table.

The staff_command_replay_lookup row is the exact production receipt lookup against ticket_audits, including the first-event metadata projection and limit-2 duplicate detection. Migration V14’s partial replay index remains installed in both phases; the before/after candidate-index comparison does not remove this command-path correctness index.

The `before` phase temporarily removes only `tickets_assignee_status_cursor_idx` and `audit_activity_projection_actor_cursor_idx`. The `after` phase recreates their exact current migration definitions. This run does not add a schema index; it validates whether the existing candidates earn their storage cost.

## Required access-audit write overhead (PERF-003)

| Phase | Samples | p50 (ms) | p95 (ms) | Throughput (ops/s) | Rows/op | Relation B/op | WAL B/op |
|---|---:|---:|---:|---:|---:|---:|---:|
| `without_required_access_audit` | 50 | 0.157 | 0.369 | 5490.282 | 0.000 | 0.000 | 40.480 |
| `with_required_access_audit` | 50 | 0.606 | 1.141 | 1483.944 | 2.000 | 2621.440 | 15196.160 |

Recorded deltas: p50 +286.0%, p95 +209.2%, throughput -73.0%.

This is a single-client database-component comparison, not an HTTP benchmark. Each sample commits the production repository’s three ticket-detail SELECT shapes; the audited phase adds the production `API_RESOURCE_READ` INSERT column/value shape and its projection trigger, using deterministic synthetic identifiers and a synthetic session fingerprint. It excludes Spring/JDBC mapping, authorization objects, assignment-option loading, JSON, network and browser time. The without-audit path is counterfactual only: Deskseed keeps strict availability semantics, so a sensitive read succeeds only after its required canonical audit write commits. `relation_bytes_delta` has PostgreSQL page-allocation granularity; `wal_bytes_delta` captures the transaction-level byte cost. The audited row amplification is one immutable `access_audit_events` row plus one `audit_activity_projection` row per successful read.

## Durable raw evidence

- `plans-before.txt` / `plans-after.txt`: raw `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`
- `latency-before.csv` / `latency-after.csv`: p50/p95 from 15 measured executions after one warm-up
- `query-cardinality.csv`: eligible and first-page rows for the exact production queue predicates
- `queue-latency-budget.csv`: fixed PERF-001 p95 ceiling and per-View pass/fail
- `sizes-before.csv` / `sizes-after.csv`: heap, total-index and candidate-index sizes plus scan counts
- `fixture-load.log`, `migrations.csv`, `durations.csv`: generation and phase timing
- `environment.txt`, `database-settings.csv`: seed, exact image and database settings
- `source-manifest.txt`, `source-fingerprint-checks.txt`: captured source hashes and freeze checkpoints
- `access-audit-overhead.csv`: committed read-only baseline versus required audit-write p50/p95, throughput, row and byte amplification
- `cleanup-status.txt`: owned container/data-volume identity and post-run absence verification

## Representativeness limits

The tables and indexes come from the repository migration SQL in numeric order, but this harness does not create Flyway history; migration upgrade behavior is an operations gate. Data is deterministic and synthetic, with deliberately regular cardinality rather than production skew. Latencies are server-side, warm-cache, single-client samples in an isolated local container; they are not an API SLO or production-capacity claim. Canonical audit rows are loaded with integrity constraints active while per-row projection refresh triggers are paused, followed by the real `rebuild_audit_activity_projection()` function. Fixture load duration therefore is not an online-ingestion benchmark.
