# ADR 0048: Personal-staging private observability

## Status

Accepted — 2026-09-12

## Context

ADR 0047 and D-064 support a disposable `load` topology only. Its Alloy collector reads the Docker socket, its JVM profile agent is load-only, and its Compose profile must not be combined with the personal-staging deployment.

Personal staging needs release-candidate diagnosis with the same three useful signals—Prometheus metrics, searchable logs, and traces—without changing the public product surface or treating operational telemetry as an audit ledger. The current personal-staging deployment already uses the `production` profile, exact SHA-tagged images, split database roles, and private Compose networks. Replacing that profile with `load` would weaken those deployment guarantees.

## Decision

- Personal staging keeps `SPRING_PROFILES_ACTIVE=production` and adds the opt-in `personal-staging-observability` profile through `SPRING_PROFILES_ADDITIONAL`; it never activates `load`.
- Prometheus is the only application-metrics export path. The profile publishes `/actuator/prometheus` on management port `9090`, bound to the operator-selected private monitoring address. The public frontend never proxies that endpoint. OTLP metrics export is disabled so the application does not retry a nonexistent `localhost:4318` receiver.
- The backend emits sampled traces and safe Logback events to an internal Alloy OTLP receiver. Alloy forwards traces to Tempo and logs to Loki's native OTLP endpoint. The collector receives only the backend service over the private `application` network.
- The personal-staging collector has no Docker socket, Docker discovery, host filesystem mount, privileged mode, host PID/network namespace, node exporter, cAdvisor, or CPU profiler. It runs read-only as the image's non-root Alloy user with ephemeral collector storage.
- Loki records the OTLP resource attributes `service.name`, `service.namespace`, and `deployment.environment.name`; request and correlation IDs are bounded structured metadata, never Loki or Prometheus labels. The monitoring server must run Loki 3.0+ with `allow_structured_metadata: true` and expose its `/otlp` endpoint only on the private monitoring path.
- The repository supplies Prometheus, rule, dashboard, and runbook fragments. It does not SSH to, rewrite, or automatically reload the external monitoring server. Firewall source allowlists and monitoring-server credentials remain operator-owned.
- CPU profiling, host/container/database/Redis exporters, k6, alert delivery, production rollout, and capacity/SLA claims remain outside this decision.

## Consequences

- The default personal-staging deployment has no extra management or collector port. Enabling the overlay adds only private `9090` (backend metrics) and `12345` (Alloy self-metrics); OTLP receiver ports remain internal to the Compose network.
- Backend log delivery is explicit rather than host-wide Docker log scraping. This avoids collecting unrelated containers that may coexist on the host, but it requires the Spring Logback appender and the monitoring server's native OTLP Loki capability.
- Telemetry export failure may produce bounded operational errors and lose non-audit telemetry after retry exhaustion; it does not change ticket, audit, mail, webhook, or authorization transaction outcomes. Required audit persistence retains its existing fail-closed behavior.
- Rollback is to redeploy without `DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED=true`, then explicitly stop/remove the optional Alloy service and remove the two private Prometheus targets and dashboard/rule fragments. No product schema or public API changes are involved.

## Rejected alternatives

- Reusing `compose.observability.yaml`: it changes the active profile to `load`, enables a profiler, and assumes a disposable host.
- Reusing the load collector's Docker socket: a compromised collector could control Docker, and Docker discovery would cross the personal-staging/log-source boundary.
- Pushing Micrometer metrics to OTLP: Prometheus pull preserves target health (`up`) and eliminates the observed fallback metrics exporter warning.

## References

- D-005, D-018, D-039, D-064, D-065
- REQ-PROD-001, REQ-OPS-001, REQ-OPS-002
- ADR 0018, ADR 0028, ADR 0047
- OPS-004, ACC-007
