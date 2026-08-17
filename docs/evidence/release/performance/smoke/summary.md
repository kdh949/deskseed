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
| `ticket_search_documents` | 10000 | 10000 | t |
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
| `queue_my_child_tasks_first_page` | 0.966 | 50 | t |
| `queue_my_open_first_page` | 1.098 | 50 | t |
| `queue_pending_first_page` | 0.818 | 50 | t |
| `queue_recently_solved_first_page` | 0.791 | 50 | t |
| `queue_unassigned_my_groups_first_page` | 1.359 | 50 | t |

## REQ-SRCH-001 local search latency budget

The fixed acceptance boundary declared before this run is warm-cache database-component p95 <= `250 ms` for both the exact result count and first score page on the recorded 1M-ticket, 2M-comment, 2-CPU / 6-GiB PostgreSQL profile. It is not an HTTP SLO.

| Query | After p95 (ms) | Budget (ms) | Pass |
|---|---:|---:|:---:|
| `search_agent_workspace_exact_count` | 3.558 | 250 | t |
| `search_agent_workspace_score_first_page` | 5.385 | 250 | t |

## Warm-cache server-side latency

| Query | Before p50 (ms) | Before p95 (ms) | After p50 (ms) | After p95 (ms) | p95 change |
|---|---:|---:|---:|---:|---:|
| `audit_action_and_date` | 0.073 | 0.082 | 0.094 | 0.245 | +198.8% |
| `audit_actor_and_date` | 0.861 | 1.375 | 0.084 | 0.089 | -93.5% |
| `audit_first_cursor_page` | 0.069 | 0.074 | 0.095 | 0.214 | +189.2% |
| `audit_projection_status` | 0.020 | 0.137 | 0.013 | 0.018 | -86.9% |
| `audit_ticket_and_date` | 0.067 | 0.077 | 0.080 | 0.116 | +50.6% |
| `queue_my_child_tasks_first_page` | 0.665 | 0.852 | 0.678 | 0.966 | +13.4% |
| `queue_my_open_first_page` | 0.754 | 0.870 | 0.795 | 1.098 | +26.2% |
| `queue_pending_first_page` | 0.737 | 0.956 | 0.739 | 0.818 | -14.4% |
| `queue_recently_solved_first_page` | 0.721 | 1.117 | 0.613 | 0.791 | -29.2% |
| `queue_unassigned_my_groups_first_page` | 1.186 | 1.468 | 1.133 | 1.359 | -7.4% |
| `search_agent_workspace_exact_count` | 3.330 | 3.620 | 3.253 | 3.558 | -1.7% |
| `search_agent_workspace_score_first_page` | 4.931 | 5.262 | 5.029 | 5.385 | +2.3% |
| `staff_command_replay_lookup` | 0.128 | 0.162 | 0.131 | 0.156 | -3.7% |

All five queue rows are exact current `StaffTicketQueryRepository.list` shapes with empty optional filters, `cursor = null`, and the API default `limit + 1 = 51`. The measured `DefaultStaffView` cases are `MY_OPEN`, `UNASSIGNED_MY_GROUPS`, `PENDING`, `RECENTLY_SOLVED`, and `MY_CHILD_TASKS`; there is no `PENDING_OR_ON_HOLD` or `RECENTLY_UPDATED` view. No synthetic queue control query is mixed into this table.

The staff_command_replay_lookup row is the exact production receipt lookup against ticket_audits, including the first-event metadata projection and limit-2 duplicate detection. Migration V14’s partial replay index remains installed in both phases; the before/after candidate-index comparison does not remove this command-path correctness index.

The audit_projection_status row is the exact list-endpoint status query: it derives transient `REBUILDING` from the advisory lock and reads the stored projection count. It does not execute `count(*)` over the projection.

The `before` phase temporarily removes only `tickets_assignee_status_cursor_idx` and `audit_activity_projection_actor_cursor_idx`. The `after` phase recreates their exact current migration definitions. This run does not add a schema index; it validates whether the existing candidates earn their storage cost.

## Required access-audit write overhead (PERF-003)

| Phase | Samples | p50 (ms) | p95 (ms) | Throughput (ops/s) | Rows/op | Relation B/op | WAL B/op |
|---|---:|---:|---:|---:|---:|---:|---:|
| `without_required_access_audit` | 50 | 0.179 | 0.438 | 4543.802 | 0.000 | 0.000 | 40.000 |
| `with_required_access_audit` | 50 | 0.508 | 1.157 | 1663.340 | 2.000 | 2621.440 | 15342.080 |

Recorded deltas: p50 +183.8%, p95 +164.2%, throughput -63.4%.

This is a single-client database-component comparison, not an HTTP benchmark. Each sample commits the production repository’s three ticket-detail SELECT shapes; the audited phase adds the production `API_RESOURCE_READ` INSERT column/value shape and its projection trigger, using deterministic synthetic identifiers and a synthetic session fingerprint. It excludes Spring/JDBC mapping, authorization objects, assignment-option loading, JSON, network and browser time. The without-audit path is counterfactual only: Deskseed keeps strict availability semantics, so a sensitive read succeeds only after its required canonical audit write commits. `relation_bytes_delta` has PostgreSQL page-allocation granularity; `wal_bytes_delta` captures the transaction-level byte cost. The audited row amplification is one immutable `access_audit_events` row plus one `audit_activity_projection` row per successful read.

## Durable raw evidence

- `plans-before.txt` / `plans-after.txt`: raw `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`
- `latency-before.csv` / `latency-after.csv`: p50/p95 from 15 measured executions after one warm-up
- `query-cardinality.csv`: eligible and first-page rows for the exact production queue predicates
- `queue-latency-budget.csv`: fixed PERF-001 p95 ceiling and per-View pass/fail
- `search-latency-budget.csv`: fixed REQ-SRCH-001 p95 ceiling for exact count and first score page
- `sizes-before.csv` / `sizes-after.csv`: heap, total-index and candidate-index sizes plus scan counts
- `fixture-load.log`, `migrations.csv`, `durations.csv`: generation and phase timing
- `environment.txt`, `database-settings.csv`: seed, exact image and database settings
- `source-manifest.txt`, `source-fingerprint-checks.txt`: captured source hashes and freeze checkpoints
- `access-audit-overhead.csv`: committed read-only baseline versus required audit-write p50/p95, throughput, row and byte amplification
- `cleanup-status.txt`: owned container/data-volume identity and post-run absence verification

## Representativeness limits

The tables and indexes come from the repository migration SQL in numeric order, but this harness does not create Flyway history; migration upgrade behavior is an operations gate. Data is deterministic and synthetic, with deliberately regular cardinality rather than production skew. Latencies are server-side, warm-cache, single-client samples in an isolated local container; they are not an API SLO or production-capacity claim. Canonical audit rows are loaded with integrity constraints active while per-row audit and ticket-search projection refresh triggers are paused, followed by the real `rebuild_audit_activity_projection()` and `rebuild_ticket_search_documents()` functions. Fixture load duration therefore is not an online-ingestion benchmark.
