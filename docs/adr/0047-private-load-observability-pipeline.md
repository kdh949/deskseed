# ADR 0047: Private load observability pipeline

## Status

Accepted — 2026-09-02

## Context

Deskseed needs reproducible HTTP and WebSocket load evidence without introducing a second monitoring stack. The supported first target is an isolated load environment connected over a private VPN to an existing Prometheus, Loki, Tempo, Pyroscope, and Grafana server. The application already emits a few Micrometer metrics, but it does not expose a Prometheus registry or export centralized logs, traces, profiles, host, database, or Redis saturation signals.

Load evidence must not weaken the existing audit, privacy, or deployment boundaries. Telemetry is operational evidence rather than an audit ledger, and no raw comment, search query, email, credential, session value, or protected audit content may become a metric label, log field, span attribute, or profile label.

## Decision

- Prometheus pulls application and exporter metrics over the VPN. Every published metrics port binds only to an explicitly configured private address and the monitoring server is the only allowed source.
- A load-only Grafana Alloy collector batches OTLP traces to Tempo and Docker logs to Loki. Its Docker access is accepted only on the disposable load host and is not an approved production topology.
- The JVM pushes JFR wall profiles directly to Pyroscope with a 10 ms sampling interval and 15 s upload interval. The pinned Pyroscope Java agent remains the profiling engine. `io.pyroscope:otel:2.1.2` contributes only a `PyroscopeOtelSpanProcessor` to Spring Boot's existing tracer provider, marks sampled root spans with `pyroscope.profile.id`, and never creates another OTel SDK or Java OTel agent. Allocation (`512k`) and lock (`10ms`) profiling are separate short-lived opt-in diagnostics rather than the default load-test mode.
- k6 runs from a host separate from the Deskseed application and monitoring server. It sends bounded run metrics to Prometheus remote write and writes a local JSON summary.
- Pushgateway is not used for the application, workers, exporters, or k6. Its stale-series and missing `up` semantics are unsuitable for these long-running targets.
- Metric labels are restricted to bounded dimensions such as service, environment, route template, status class, operation, and outcome. Request, correlation, trace, actor, ticket, email, query, raw URL, and error-message values are forbidden labels.
- Request and correlation IDs remain searchable structured log fields. Traces carry only allowlisted metadata. Operational telemetry never replaces required TicketAudit, AccessAuditEvent, SecurityEvent, or delivery records.
- The first supported topology is a disposable `load` Compose overlay. Production monitoring, automatic synchronization of the external monitoring server, alert notification delivery, and broker or Kubernetes adoption remain outside this decision.

## Operational questions

The dashboards and run evidence must answer:

1. Are clients observing errors, latency, throttling, or dropped work during a named load profile?
2. Is saturation in the JVM, Hikari pool, PostgreSQL, Redis, host, or container correlated with that symptom?
3. Are required audit writes, mail/webhook workers, or collaboration delivery failing or falling behind?
4. Can one safe synthetic request be followed through OVERALL/COUNT/PAGE/AUDIT spans, bounded source metadata, queryid/waits, a protected load-only execution plan, and the same root span's profile?

## Consequences

- The default Compose topology remains unchanged; the load observability overlay is opt-in and rollback is removal of that overlay and its private firewall rules.
- Public product OpenAPI contracts do not change. The Prometheus endpoint is a private management surface and is never proxied by the public frontend.
- Capacity, SLA, and bottleneck claims remain `Not run` until a versioned dataset, environment description, k6 result, Prometheus snapshot, and database evidence are recorded together.
- Tempo `tracesToProfiles` uses `pyroscope.profile.id` and the Pyroscope datasource for direct root-span profile navigation. Short sampled spans can still have no profile sample; `No data` is not proof of no CPU/wall cost.
- `pg_stat_statements` exposes only fixed query family plus queryid/calls/time/blocks/temp/rows. Query text and bind values never enter exporter, Prometheus, or Grafana. `SearchPlanCapture` may run `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` only against an explicitly identified load DB with a synthetic corpus and protected artifact output.

## References

- D-005, D-018, D-039, D-060, D-064
- REQ-PROD-001, REQ-OPS-001, REQ-PERF-001, REQ-PERF-002, REQ-COL-002, REQ-AUTH-003
- OPS-004, PERF-001/002/003/004, CHN-012, AUTH-006
