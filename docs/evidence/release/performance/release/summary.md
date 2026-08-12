# release release performance evidence

- Result: `PASS`
- Command: `bash scripts/run-release-performance.sh --scale release`
- Fixture seed: `424242`; base time: `2026-08-12T00:00:00Z`
- PostgreSQL: `17.10`; container limit: `2 CPU / 6g memory`

## Verified counts

| Entity | Actual | Expected | Match |
|---|---:|---:|:---:|
| `access_audit_events` | 500000 | 500000 | t |
| `admin_security_audit_events` | 100000 | 100000 | t |
| `audit_activity_projection` | 1600000 | 1600000 | t |
| `customers` | 100000 | 100000 | t |
| `dangling_comment_ticket_refs` | 0 | 0 | t |
| `dangling_ticket_audit_refs` | 0 | 0 | t |
| `search_audit_details` | 50000 | 50000 | t |
| `ticket_audit_events` | 1000000 | 1000000 | t |
| `ticket_audits` | 1000000 | 1000000 | t |
| `ticket_comments` | 2000000 | 2000000 | t |
| `ticket_relations` | 10000 | 10000 | t |
| `tickets` | 1000000 | 1000000 | t |

## Production queue cardinality

| Query | Eligible rows | Returned first-page rows | Representative |
|---|---:|---:|:---:|
| `queue_my_child_tasks_first_page` | 8000 | 51 | t |
| `queue_my_open_first_page` | 2200 | 51 | t |
| `queue_pending_first_page` | 200000 | 51 | t |
| `queue_recently_solved_first_page` | 2200 | 51 | t |
| `queue_unassigned_my_groups_first_page` | 1600 | 51 | t |

## PERF-001 local queue latency budget

The fixed acceptance boundary declared before this run is warm-cache database-component p95 <= `50 ms` for every exact DefaultStaffView query on the recorded 2-CPU / 6-GiB release container profile, with one bounded joined SQL statement, a 51-row first page, and representative fixture cardinality.

| Query | After p95 (ms) | Budget (ms) | Pass |
|---|---:|---:|:---:|
| `queue_my_child_tasks_first_page` | 0.362 | 50 | t |
| `queue_my_open_first_page` | 0.536 | 50 | t |
| `queue_pending_first_page` | 0.670 | 50 | t |
| `queue_recently_solved_first_page` | 0.367 | 50 | t |
| `queue_unassigned_my_groups_first_page` | 9.367 | 50 | t |

## Warm-cache server-side latency

| Query | Before p50 (ms) | Before p95 (ms) | After p50 (ms) | After p95 (ms) | p95 change |
|---|---:|---:|---:|---:|---:|
| `audit_action_and_date` | 0.074 | 0.081 | 0.075 | 0.081 | +0.0% |
| `audit_actor_and_date` | 6.018 | 12.666 | 0.074 | 0.079 | -99.4% |
| `audit_first_cursor_page` | 0.068 | 0.074 | 0.069 | 0.075 | +1.4% |
| `audit_projection_status` | 0.012 | 0.016 | 0.013 | 0.017 | +6.3% |
| `audit_ticket_and_date` | 0.061 | 0.068 | 0.063 | 0.069 | +1.5% |
| `queue_my_child_tasks_first_page` | 0.339 | 0.353 | 0.346 | 0.362 | +2.5% |
| `queue_my_open_first_page` | 0.651 | 0.769 | 0.368 | 0.536 | -30.3% |
| `queue_pending_first_page` | 0.637 | 0.888 | 0.650 | 0.670 | -24.5% |
| `queue_recently_solved_first_page` | 0.620 | 0.717 | 0.354 | 0.367 | -48.8% |
| `queue_unassigned_my_groups_first_page` | 36.560 | 40.168 | 8.440 | 9.367 | -76.7% |
| `staff_command_replay_lookup` | 0.128 | 0.138 | 0.128 | 0.141 | +2.2% |

All five queue rows are exact current `StaffTicketQueryRepository.list` shapes with empty optional filters, `cursor = null`, and the API default `limit + 1 = 51`. The measured `DefaultStaffView` cases are `MY_OPEN`, `UNASSIGNED_MY_GROUPS`, `PENDING`, `RECENTLY_SOLVED`, and `MY_CHILD_TASKS`; there is no `PENDING_OR_ON_HOLD` or `RECENTLY_UPDATED` view. No synthetic queue control query is mixed into this table.

The staff_command_replay_lookup row is the exact production receipt lookup against ticket_audits, including the first-event metadata projection and limit-2 duplicate detection. Migration V14’s partial replay index remains installed in both phases; the before/after candidate-index comparison does not remove this command-path correctness index.

The audit_projection_status row is the exact list-endpoint status query: it derives transient `REBUILDING` from the advisory lock and reads the stored projection count. It does not execute `count(*)` over the projection.

The `before` phase temporarily removes only `tickets_assignee_status_cursor_idx` and `audit_activity_projection_actor_cursor_idx`. The `after` phase recreates their exact current migration definitions. This run does not add a schema index; it validates whether the existing candidates earn their storage cost.

## Required access-audit write overhead (PERF-003)

| Phase | Samples | p50 (ms) | p95 (ms) | Throughput (ops/s) | Rows/op | Relation B/op | WAL B/op |
|---|---:|---:|---:|---:|---:|---:|---:|
| `without_required_access_audit` | 100 | 0.148 | 0.315 | 6065.017 | 0.000 | 0.000 | 40.000 |
| `with_required_access_audit` | 100 | 0.646 | 1.950 | 1270.003 | 2.000 | 2457.600 | 22262.560 |

Recorded deltas: p50 +336.5%, p95 +519.0%, throughput -79.1%.

This is a single-client database-component comparison, not an HTTP benchmark. Each sample commits the production repository’s three ticket-detail SELECT shapes; the audited phase adds the production `API_RESOURCE_READ` INSERT column/value shape and its projection trigger, using deterministic synthetic identifiers and a synthetic session fingerprint. It excludes Spring/JDBC mapping, authorization objects, assignment-option loading, JSON, network and browser time. The without-audit path is counterfactual only: Deskseed keeps strict availability semantics, so a sensitive read succeeds only after its required canonical audit write commits. `relation_bytes_delta` has PostgreSQL page-allocation granularity; `wal_bytes_delta` captures the transaction-level byte cost. The audited row amplification is one immutable `access_audit_events` row plus one `audit_activity_projection` row per successful read.

## Durable raw evidence

- `plans-before.txt` / `plans-after.txt`: raw `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`
- `latency-before.csv` / `latency-after.csv`: p50/p95 from 30 measured executions after one warm-up
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
