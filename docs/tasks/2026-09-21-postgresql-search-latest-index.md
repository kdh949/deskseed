# PostgreSQL latest-first search index slice

## Scenario and actor

An authenticated active `STAFF` actor using source `AGENT_UI` searches the one-million-ticket personal
staging corpus and asks for the latest matching page. Broad literal substring matches must not spend
seconds sorting an unbounded candidate set when PostgreSQL can instead use the requested canonical
ticket order as a bounded page path.

## Contract

- Requirements: `REQ-SRCH-001`, `REQ-PERF-001`, `REQ-PERF-002`, `REQ-OPS-002`.
- Decisions: `D-064`, `D-065`, `D-067`, `D-068`, `D-069`, `D-070`, `D-071`.
- ADRs: 0047, 0048, 0050, 0051, 0052, 0053, 0054.
- Verification gates: `ARCH-001~004`, `SEARCH-AUD-001/002`, `PERM-001`, `PERF-001/003`,
  `OPS-004`, `DOC-001`.
- API operation: `searchAgentWorkspace`; response and cursor contracts do not change.

## Boundaries

- PUBLIC/INTERNAL projection, current-ticket authorization, deletion checks, active/terminal order,
  required search audit, query redaction/fingerprint/ciphertext policy, and fail-closed behavior stay
  unchanged.
- Flyway owns one additive B-tree index. There is no data mutation beyond index construction, no new
  database or replica, no cache, and no pool/global PostgreSQL setting change.
- The migration is independent of request transaction, idempotency, retry, and external I/O paths.
- The new index increases storage and ticket-write maintenance; exact size and observed latency are
  recorded after the exact-SHA personal-staging deploy.

## Measured trigger and acceptance

- Deployed ADR 0053 SHA `547c300` completed all fixed queries, but repeated `topic` requests reached
  3.38~3.55 seconds and a sampled repeat recorded HTTP 4.166 seconds / server 3.84 seconds /
  `PAGE` 3.81 seconds.
- The matching latest-first query ID recorded maximum execution 3.508 seconds and temporary writes;
  this supports an order-path candidate but not an executor-node conclusion.
- Accept only if the same `topic:3` sampled request and fixed 20-query repeats materially reduce the
  tail without timeout, error, drop, permission, deletion, audit, health, restart, or recovery failure.
- Generator resource telemetry remains absent, so this slice does not authorize 5/10 req/s capacity
  escalation or an SLA claim.

