# Load Observability Foundation Task Brief

## Goal

운영자가 기존 private monitoring server를 재사용해 격리된 Deskseed load 환경의 metrics, logs, traces, profiles와 k6 결과를 함께 조회하고 재현 가능한 부하 증거를 남길 수 있다.

## Decision and source references

- Decision IDs: D-005, D-018, D-039, D-060, D-064
- Accepted ADRs: 0018, 0028, 0043, 0047
- Requirements: REQ-PROD-001, REQ-OPS-001, REQ-PERF-001, REQ-PERF-002, REQ-COL-002, REQ-AUTH-003
- API operations: no product OpenAPI operation change; private `/actuator/prometheus` management endpoint only
- Verification gates: OPS-004, PERF-001/002/003/004, CHN-012, AUTH-006

## Actor and source

- Actor: SYSTEM for telemetry emission; authenticated synthetic CUSTOMER/STAFF for load scenarios
- Source: load-only Compose deployment and an independently operated k6 host
- Required access: VPN-private monitoring endpoints; normal customer/staff HTTP and WebSocket authorization remains unchanged
- Correlation: safe synthetic requests carry bounded request/correlation IDs; these values are log/span fields, never metric labels

## In scope

- private Prometheus endpoint and bounded application metrics
- structured safe logs, OTLP traces, and load-only CPU profiles
- node, container, PostgreSQL, and Redis exporters
- monitoring-server configuration fragments, Grafana dashboards/rules, and a manual apply/rollback runbook
- authenticated agent-read, public-request, customer-auth limiter, and collaboration WebSocket k6 scenarios
- versioned run evidence that separates Passed, Pending, Not run, and Skipped

## Out of scope

- public or production observability endpoints
- automatic SSH/Ansible/CI mutation of the existing monitoring server
- Alertmanager or external notification delivery
- production Docker-socket collector approval
- direct span-to-profile linking, distributed k6, capacity optimization, Kafka, Kubernetes, or new caches

## Invariants and failure semantics

- Telemetry failure never changes canonical ticket, audit, mail, webhook, or collaboration transaction semantics.
- Required audit persistence still fails closed; operational counters only report the failure.
- No external network call is added inside a ticket transaction.
- Load writes target disposable load data only and require explicit target and destructive-write confirmation.
- Expected throttling is reported separately from unexpected 4xx/5xx responses.

## Data and privacy

- Allowed labels: service, environment, instance, route template, status class, bounded operation/outcome, k6 scenario/profile.
- Forbidden telemetry: raw body, comment, note, search query, email, password, token, cookie, Authorization value, audit ciphertext, actor/ticket identity, full URL, unbounded exception text.
- Loki stores request/correlation/trace IDs as fields, not labels. Prometheus receives no per-request identity.
- Synthetic identities use reserved `.invalid` addresses and the load database is disposable.

## Acceptance scenarios

- Given the default Compose files, when the observability overlay is omitted, then no new management/exporter port is published.
- Given the load overlay and private bind address, when Prometheus scrapes the declared targets, then each target is `up=1` and the public frontend still denies `/actuator/prometheus`.
- Given one safe synthetic request, when it completes, then its correlation ID locates a JSON log and trace while no protected content appears in either.
- Given a smoke k6 run, when metrics are remote-written, then Grafana shows request rate, latency histogram, status classes, VUs, and dropped iterations for the same run window.
- Given audit, Hikari, Redis, worker, or WebSocket failure/saturation, when the bounded metric changes, then the dashboard and corresponding load-environment rule expose the condition without changing domain state.

## Validation

- `make docs-check`
- `docker compose -f compose.yaml -f compose.observability.yaml --profile observability config --quiet`
- Backend focused fast/contract/integration tests for management exposure, redaction, and custom metrics
- `promtool check config` and `promtool check rules` for the supplied fragments
- Alloy configuration validation and `nginx -t`
- k6 script inspection, one-VU smoke, and four-signal ingest check
- AUTH-006 and CHN-012 remain `Not run` until the supported deployment runs are recorded

## Compatibility and rollback

- OpenAPI: no change.
- Migration: no product schema migration. `pg_stat_statements` is a load-host operator setting only.
- Existing clients: unchanged.
- Rollback: stop the overlay, remove its monitoring-server scrape/rule/dashboard fragments, and remove private firewall allowances. Canonical application data is unchanged.

## Human explanation

- Pull metrics preserve Prometheus target health; push is limited to logs, traces, profiles, and k6 run results that naturally originate outside scrape semantics.
- The existing monitoring server is reused without making the Deskseed repository an authority that mutates it remotely.
- Measurement identifies a bottleneck before any cache, broker, pool, index, or architecture change is proposed.

