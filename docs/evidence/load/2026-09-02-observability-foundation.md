# Deskseed load observability foundation evidence

## Result

The repository-owned foundation is `PASS` for static configuration and focused application verification. Live four-signal ingestion and service capacity remain `NOT RUN` because no private monitoring-server address, load deployment, synthetic credentials, or destructive-write approval was supplied.

This evidence covers branch `feature/deskseed-load-observability` through code commit `e8fbab2` and the following vertical slices:

| Commit | Slice |
|---|---|
| `601d612` | operator contract, D-064, ADR 0047, REQ-OPS-001 and REQ-PERF-002 |
| `b2b2122` | private management endpoint, Prometheus metrics, structured logs and sampled OTLP traces |
| `ae3519d` | bounded customer authentication limiter metrics |
| `6deff08` | load Compose overlay, Alloy/exporters, Pyroscope agent, Prometheus rules and Grafana dashboard |
| `5a10bca` | guarded authenticated HTTP and collaboration WebSocket k6 scenarios |
| `e8fbab2` | bounded WebSocket and required audit-persistence failure metrics |

## Actor, scope and invariant evidence

- Telemetry actor is `SYSTEM`; load traffic still authenticates as synthetic CUSTOMER or STAFF through existing product surfaces.
- No OpenAPI operation, product schema, permission, ticket state, audit event, or canonical transaction behavior changed.
- Access and admin-security audit failures are counted and the original exception is rethrown. Required audit persistence remains fail-closed.
- Metric labels are restricted to bounded service/environment/operation/outcome/code/type values. Request, correlation, actor, ticket, email, query, token, cookie and body values are not metric labels.
- The load runner defaults to a one-VU smoke. Non-smoke profiles require exact target confirmation; write scenarios additionally require destructive-write confirmation and reserved `.invalid` identities.
- Management and exporter ports are absent from the base Compose path and are published only by the observability overlay to an operator-supplied private bind address.

## Verification results

### Passed

- `make docs-check`
- `./scripts/validate-observability-config.sh`
  - merged Compose configuration
  - Alloy configuration validation
  - Prometheus 3.14 configuration and eight alert rules
  - Nginx configuration and public Actuator denial route
  - Grafana dashboard JSON
  - all four k6 2.0 scenario inspections
- `docker build -f backend/Dockerfile -t deskseed-backend:observability-test .`
  - backend boot JAR build
  - Pyroscope Java agent 2.9.1 SHA-256 verification
- `./gradlew --no-daemon fastTest`
  - 123 categorized fast tests, including load configuration, limiter metrics and audit-persistence metrics
- `./gradlew --no-daemon contractTest`
  - 20 categorized contract tests
- focused `StaffCollaborationWebSocketIntegrationTest`
  - three tests covering connection gauge, authorization revocation and rate-limit rejection counter

The sandbox prevented the Kotlin daemon from writing under the user Library directory, so Gradle used its documented in-process fallback. Spring test shutdown also logged an expected connection warning because no local OTLP receiver was running. Both Gradle tasks completed successfully.

### Not run

- deployment of the load Compose stack against a private load host
- monitoring-server Prometheus reload, dashboard import, rule test-fire or notification delivery
- `up=1` validation for backend, node, container, PostgreSQL and Redis scrape targets
- Loki/Tempo/Pyroscope ingestion and correlation/privacy smoke
- k6 one-VU execution or any non-smoke arrival-rate profile
- telemetry-on/off overhead A/B, AUTH-006, CHN-012, PERF-004 and capacity/SLA measurement
- full integration, migration and slow test suites; only the changed WebSocket slow integration class ran

## Compatibility, rollback and interpretation

- Compatibility: existing API clients and default Compose users are unchanged. The OpenAPI contract and product migrations are unchanged.
- Rollback: stop/remove `compose.observability.yaml`, remove the Deskseed monitoring fragments and private firewall allowances, and revert the six code commits. No canonical data cleanup is required.
- Interpretation: this is implementation and harness evidence, not proof that the external monitoring server accepts all signals and not a Deskseed throughput or latency claim.

The central trade-off is intentional: Prometheus continues to pull application and host metrics so scrape health remains explicit, while Alloy/OTLP/Pyroscope and k6 remote write handle signals that naturally originate on the load host. The repository supplies validated fragments but does not become an automation authority over the existing monitoring server.
