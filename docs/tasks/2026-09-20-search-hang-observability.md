# Search hang observability and bounded reproduction

Status: implementation and live verification in progress

## Scope

- User scenario: an authenticated active staff member performs agent ticket search in personal staging while a separate generator supplies a bounded, reproducible workload.
- Objective: distinguish arrival, user-visible HTTP duration, application search/audit work, response assembly, collector delivery, continuous CPU profiling, and recovery using Grafana evidence.
- Out of scope: result accuracy/ranking, product query semantics, index changes, Hikari tuning, direct production SQL/EXPLAIN, raw search text or bind capture.

## Traceability

- Requirements: `REQ-SRCH-001`, `REQ-OPS-002`, `REQ-PERF-001`, `REQ-PERF-002`.
- Decisions: D-009, D-058; Accepted ADR-0047 and ADR-0048.
- Verification gates: `ACC-007`, `SEARCH-AUD-001`, `SEARCH-AUD-002`, `OPS-004`, `PERF-001`, `PERF-003`.
- API contract: `POST /api/v1/agent/search` remains unchanged.

## Data and security boundaries

- The HTTP diagnostic span starts after request/correlation IDs are assigned and ends after Spring MVC response conversion returns or fails.
- `overall` remains the transactional application-service boundary. `response_assembly` covers only the bounded response DTO construction step. Residual time is derived from the enclosing HTTP span and is not silently attributed to serialization.
- Every diagnostic span has bounded `environment`, `query_class`, `phase`, outcome, and validated test-run/case identifiers. Metrics retain only bounded phase/class/outcome labels.
- Raw query, request/response body, customer/account identifiers, bind values, SQL text, and exception messages are not recorded.
- Required search audit persistence and transaction semantics are unchanged; telemetry failures cannot change the business result.

## Delivery and rollback

- The personal-staging diagnostics overlay opts into full trace sampling only while explicitly included. Normal personal staging remains at its configured `0.05` default.
- The diagnostics overlay also enables the existing Pyroscope Java agent and bounded span/profile correlation. Allocation and lock profiling stay disabled.
- Rollback is removal of the diagnostics overlay and redeployment of the previously recorded SHA. No migration or persisted product-data change is involved.

## Live verification plan

1. Prove exact image SHA/OCI revision, one-shot container exit, frontend, aggregate health, and private Prometheus endpoint.
2. Before load, prove `process_cpu` profile ingest and collector received/exported/failed/refused signals in Grafana.
3. Execute one `single-smoke` search with a unique `TEST_RUN_ID`; preserve k6/local receipt separately from Grafana evidence.
4. Require one trace containing HTTP overall, application overall, page SQL, audit, and response assembly plus matching profile signal.
5. Only then run the prior workload with the same corpus, input mix, weights and seed under a hard timeout. Stop on the plan's error, drop, audit, pool, or recovery criteria.
6. Classify observations as fact, candidate, confirmed cause, or indeterminate. In particular, exact COUNT and broad substring scan remain candidates until Grafana evidence isolates them.

