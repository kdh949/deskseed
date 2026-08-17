# Release-scale database performance evidence

This directory holds bounded, sanitized evidence for exact Agent View queue queries,
unified Audit Explorer paths, and required sensitive-read audit overhead. It measures the
current PostgreSQL schema; it does not introduce a cache, search engine, or new service.

> **Current artifact status:** `release/` was regenerated on 2026-08-18 (Asia/Seoul) from
> the current runner and V1-V35 schema. The release artifact is `PASS`, records 56 relevant
> source hashes with no mismatch at six freeze checkpoints, and covers all five
> `DefaultStaffView` shapes, two Agent Workspace search plans and p50/p95 samples, four Audit Explorer page
> reads, the exact O(1) projection-status read, and the staff-command replay lookup. Its
> host-filesystem preflight passed with 195.32 GiB free against a 21 GiB guard; no low-disk
> override was used. Exact container, anonymous data-volume, and scratch cleanup passed.
> Docker Desktop VM quota remains a separately stated operator check rather than a measured
> guarantee.

## Reproduce

Validate the harness first:

```bash
bash scripts/run-release-performance.sh --scale smoke
```

Then run the release fixture with at least the preflight value derived from the latest
smoke evidence free in the filesystem used by Docker Desktop (currently 21 GiB; the
fixed floor is 16 GiB):

```bash
bash scripts/run-release-performance.sh --scale release
```

The release profile defaults are deterministic and intentionally explicit:

| Row family | Count |
|---|---:|
| Customer | 100,000 |
| Ticket | 1,000,000 |
| TicketSearchDocument | 1,000,000 |
| Comment | 2,000,000 (one PUBLIC and one INTERNAL per ticket) |
| TicketAudit / TicketAuditEvent | 1,000,000 each |
| AccessAuditEvent | 500,000 |
| SearchAuditDetail | 50,000 |
| AdminSecurityAuditEvent | 100,000 |
| AuditActivityProjection | 1,600,000 |

The canonical `release` artifact accepts only the documented image, seed, base time,
cardinalities, 30 queue samples, 100 access-overhead samples, and 2 CPU / 6 GiB limit;
profile overrides fail before Docker starts. Validated `PERF_*` overrides are for `smoke`
diagnostics and must use a separately owned output directory when they differ from the
default smoke profile. This prevents a larger machine or different fixture from
publishing a false PERF-001 pass into `release/`.

The disk guard uses the measured smoke relation sizes, a 3x transient/WAL allowance and
a 4 GiB host reserve, with a 16 GiB floor for the default release fixture. Only
`DESKSEED_PERF_ALLOW_LOW_DISK=1` bypasses it. That explicit override is recorded and means
the operator accepts filesystem-exhaustion risk; it does not reduce the fixture or turn
an incomplete run into release evidence. The guard reads the host repository filesystem;
it cannot see a separate Docker data-root filesystem or Docker Desktop VM disk quota.
Before a release run, the operator must also verify that Docker's configured disk
allocation has at least the same headroom. Every generated `preflight.md` records this
measurement boundary.

The runner rejects a `release` profile below the documented Customer, Ticket, Comment,
audit-cardinality, staff/group-cardinality, 30 queue samples, or 100 access-overhead
samples. Use `--scale smoke` for smaller custom harness checks; a reduced run cannot
produce release-scale evidence.

The ticket distribution keeps assignment valid: every assigned staff member belongs to
the ticket group, 15% of tickets are grouped but unassigned, and 5% are ungrouped and
unassigned. Grouped-unassigned rows are distributed deterministically across active
groups so the production `UNASSIGNED_MY_GROUPS` membership subquery has nonzero,
first-page cardinality at both scales.
Every 100th ticket is an `INTERNAL_CHILD` assigned to staff 42 and that staff member's
active group, producing 80 eligible child tasks in smoke and 8,000 at release scale.

## Exact queue shapes and before/after meaning

The measured queue statements are snapshots of
`StaffTicketQueryRepository.list(DefaultStaffView, ...)` with empty optional filters,
`cursor = null`, and the HTTP default page size 50 passed to the repository as 51:

- `queue_my_open_first_page`
- `queue_unassigned_my_groups_first_page`, including active staff/group membership
- `queue_pending_first_page`
- `queue_recently_solved_first_page`, including the 30-day cutoff
- `queue_my_child_tasks_first_page`, including child kind, actor assignment, and
  non-solved/non-closed predicates

The five measured product cases cover the complete current `DefaultStaffView` enum; it
has no `PENDING_OR_ON_HOLD` or `RECENTLY_UPDATED` view. The harness contains no
synthetic status-only or fixed-group control query disguised as a product View. If the
repository shape changes, both `release-performance-explain.sql` and
`release-performance-latency.sql` must change in the same release-hardening PR.

The migrations already contain the relevant candidate indexes. After the fixture is
loaded, the harness temporarily drops these two definitions in its disposable database:

```sql
create index tickets_assignee_status_cursor_idx
    on tickets (assignee_id, status, updated_at desc, ticket_number desc);

create index audit_activity_projection_actor_cursor_idx
    on audit_activity_projection (actor_id, occurred_at desc, id desc);
```

The `before` plans and latency are captured without them. The harness then recreates the
exact definitions, restarts PostgreSQL to clear its buffer cache for the first plan, and
captures the `after` evidence. The p50/p95 samples run after one unrecorded warm-up and
measure server-side execution through PL/pgSQL; they exclude HTTP, JSON serialization,
network and browser time.

The V35 ticket-search trigram index remains installed in both queue candidate-index phases;
it is a correctness/performance index for the production search path rather than a temporary candidate.
Only `MY_OPEN`/`RECENTLY_SOLVED` are expected to respond directly to the ticket candidate
index, and only actor+date is expected to respond directly to the audit candidate index.
Sub-millisecond changes in unaffected queries are measurement noise, not optimization
claims.

## PERF-001 gate assessment

Before the current artifact was generated, the repository declared a fixed warm-cache
database-component p95 ceiling of 50 ms for each exact View on the PostgreSQL 17, 2 CPU /
6 GiB, 1M-ticket profile. The same prospective boundary requires one joined SQL statement,
`limit + 1 = 51`, no row-by-row display-label lookup, and representative cardinality.
The runner records the budget documents in its source manifest and rejects any View over
the threshold. The current artifact therefore passes PERF-001; this remains a local
component gate, not a production SLO.

| Exact production View | Eligible rows | First page | After p95 (ms) | After-plan observation |
|---|---:|---:|---:|---|
| `MY_OPEN` | 2,200 | 51 | 0.679 | `tickets_assignee_status_cursor_idx`; bounded `LIMIT 51` |
| `UNASSIGNED_MY_GROUPS` | 1,600 | 51 | 10.494 | membership query plus bounded top-N first page |
| `PENDING` | 200,000 | 51 | 0.797 | `tickets_status_cursor_idx`; stops after the first 51 rows |
| `RECENTLY_SOLVED` | 2,200 | 51 | 0.453 | assignee/status/cursor index with the 30-day predicate |
| `MY_CHILD_TASKS` | 8,000 | 51 | 0.374 | exact child-kind/actor/non-terminal predicate; bounded first page |

These numbers come directly from
[`release/latency-after.csv`](release/latency-after.csv), while eligible and returned rows
come from [`release/query-cardinality.csv`](release/query-cardinality.csv). The raw plan
and buffer evidence is [`release/plans-after.txt`](release/plans-after.txt). All five
queries share `StaffTicketQueryRepository.list`, which issues one joined statement for a
page; `StaffTicketQueryEvidenceIntegrationTest` guards that bounded query count and the
absence of row-level label lookups on the `MY_OPEN` shape.

The raw budget table is
[`release/queue-latency-budget.csv`](release/queue-latency-budget.csv). The earlier
08:22 artifact remains a baseline only because it preceded the declared budget; it was
not retroactively reclassified.

The current list-endpoint status query is separately captured as
`audit_projection_status`: its release p95 is 0.016 ms and the raw plan reads the single
`audit_activity_projection_state` row plus advisory-lock state. It does not scan or count
the 1.6-million-row projection.

## Agent Workspace search projection

The PostgreSQL implementation keeps its active-staff authorization predicate in SQL and
runs the exact count and stable `score,ticketNumber` first page independently. V35 replaces
the original broad canonical-table scans (5,371.400 ms / 6,374.443 ms) with a versioned,
transactionally refreshed staff-only trigram projection. The same 301-result release query
uses the ticket-number and GIN indexes and records warm-cache p95 of 0.699 ms for count and
2.883 ms for the first score page, both below the prospective 250 ms local DB-component
budget. This is not a production HTTP SLO. See
[Agent Workspace Search — one-million-ticket baseline and V35 projection evidence](p1-agent-search-1m.md)
for consistency, privacy, index-size, quality-corpus, and remaining write/concurrency limits.

Admin organization lists are guarded separately at the PostgreSQL integration-test layer.
Staff, group and active-member endpoints retain the frozen array body but accept zero-based
`page` and a maximum `size` of 100, return total/page headers, and batch their related
memberships, group labels, member counts and explicit audit grants. The regression fixture
grows each relevant row family from more than one full page to 27 rows, asserts unchanged
Hibernate prepared-statement counts, and caps every request at 10 statements. This is a
query-amplification regression gate, not release-scale latency evidence or a production SLO.

## Required access-audit overhead (PERF-003)

`release-performance-access-overhead.sql` runs a single-client database-component A/B.
Every sample commits the same three SQL shapes used by
`StaffTicketQueryRepository.findDetail(5242)`. The audited phase adds the exact
`JpaAccessAuditWriter` `API_RESOURCE_READ` insert, including the real projection trigger
and synchronous commit. It records p50/p95, sequential throughput, canonical/projection
row amplification, relation-size allocation and WAL bytes.

This is deliberately not called an HTTP benchmark: it excludes Spring/JDBC object
mapping, authorization objects, assignment-option loading, JSON, network and browser
time. The no-audit arm is a counterfactual measurement, never a supported availability
mode. Deskseed retains strict semantics: if the canonical audit write fails, protected
ticket detail is not returned successfully. The benchmark runs after fixture counts and
candidate-index sizes are captured, so its additional synthetic audit rows do not alter
those fixture assertions.

The raw files are intentionally small: thirteen plan shapes (five queues, two P1 Agent
Workspace search statements, four Audit Explorer page reads, the exact projection-status
read, and the staff-command replay lookup), query cardinality, aggregated latency
percentiles, the access-write comparison, object sizes, exact fixture counts,
database settings, image digest, harness/migration/production-query-source SHA-256 values
and phase durations. They contain no credentials, request tokens, comment bodies, raw
search queries, session cookies, or real customer data.

## Evidence publication and failure state

Each run writes to a same-filesystem staging directory. A successful run publishes the
whole evidence directory together; a failed run replaces any prior PASS summary with an
explicit `FAILED` summary, phase, exit code and partial diagnostics. `run-status.txt` is
the machine-readable terminal state. Temporary scratch files and the disposable
container are removed on exit unless `--keep-container` explicitly retains the latter.

The two repository-owned targets are exactly `smoke/` and `release/` under this evidence
root. A custom `--output-dir` is accepted only when the target does not exist or already
contains the runner’s exact ownership marker. Symlink targets, unsafe basenames,
nonexistent parents and existing unmarked directories are rejected before staging. The
same canonical-parent and exact-mktemp-prefix checks guard backup and scratch removal.

## Interpretation boundary

This is a local comparative benchmark, not a production SLO. The fixture uses repository
migration SQL and the real audit projection rebuild, but deterministic regular data
cannot model every production skew. It is single-client and warm-cache. Each migration is
applied in numeric order with `ON_ERROR_STOP` and a single transaction, but without Flyway
history, so clean install and upgrade reproducibility remain operations gates. Only a
default `release` artifact may
be used as evidence for the 100k Customer / 1M Ticket gate; a `smoke` artifact proves the
harness, SQL shape, access-overhead method and evidence pipeline only.
