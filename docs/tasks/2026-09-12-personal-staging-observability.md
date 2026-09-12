# Personal-staging Observability Task Brief

## Goal

운영자는 personal-staging의 `production` 동작을 유지한 채 private monitoring server에서 Prometheus metrics, 안전한 구조화 로그, sampled trace를 함께 확인할 수 있다.

## Decision and source references

- Decision IDs: D-005, D-018, D-039, D-064, D-065
- Accepted ADRs: 0018, 0028, 0047, 0048
- PRD/domain sections: `docs/03-architecture.md`, `docs/19-security-audit-center.md`, `docs/23-data-retention-and-privacy.md`, `docs/36-self-hosted-operations-runbook.md`
- API contract operation IDs: none; `/actuator/prometheus` is a private management surface, not product OpenAPI
- Requirement IDs: REQ-PROD-001, REQ-OPS-002
- Verification gate IDs: OPS-004, ACC-007

## Actor and source

- Actor type: SYSTEM
- Source: opt-in personal-staging Compose overlay and operator-owned private monitoring server
- Required role/scopes: deployment operator with the existing personal-staging deployment authority; monitoring-server administrator separately owns scrape/firewall/Loki/Tempo configuration
- Resource constraints: the collector accepts OTLP only from the backend on the Compose `application` network; `9090`/`12345` permit only the monitoring source over the private network
- Interaction/request/correlation semantics: request and correlation IDs are bounded structured log metadata and trace context, never Prometheus or Loki labels

## Product and UX contract

- Requirement IDs: REQ-OPS-002
- Screen IDs / route IDs: none
- OpenAPI operationIds: none
- loading/empty/error/denied/conflict states: Grafana `No data` is an ingestion/scrape state, not a healthy deployment assertion; inaccessible private ports are a network-policy failure
- keyboard/focus/accessibility requirements: not applicable; Grafana UI is operator-owned

## In scope

- `personal-staging-observability` Spring profile layered after `production`
- private backend management port, Prometheus scrape/rule/dashboard fragments
- backend-only OTLP Logback events and sampled OTLP traces through a hardened Alloy collector
- deployment-script opt-in switch, static Compose/Alloy/Prometheus/dashboard contracts, and manual runbook
- disabling the stray OTLP metrics exporter for both load and personal-staging scrape topologies

## Out of scope

- deployment to `172.16.16.19`, monitoring-server mutation, firewall mutation, or a live load test
- `load` profile activation, CPU profiler, k6, node/cAdvisor/PostgreSQL/Redis exporters, Docker socket, host filesystem collection, or host-wide log collection
- public management routes, product OpenAPI, schema migration, alert notification routing, capacity/SLA claims, and production observability rollout

## Invariants and failure semantics

- Domain invariants: telemetry remains operational evidence and never replaces canonical TicketAudit, AccessAuditEvent, SecurityEvent, or delivery rows.
- Transaction boundary: log/trace export happens outside ticket transactions; collector availability cannot change committed ticket/audit/mail/webhook/authorization results.
- Audit obligation: existing sensitive-read/write audit persistence remains fail-closed; a metric can report its failure but cannot substitute for the row.
- Audit failure behavior: unchanged; sensitive success is not returned when required audit persistence fails.
- Concurrency: no domain lock, ticket version, or database schema behavior changes.
- Idempotency/retry: OTLP batch retry is best-effort operational delivery only; duplicate/lost telemetry is not a domain replay signal.
- External I/O boundary: Alloy's network calls begin after a local application log/trace exists; no remote call runs inside a ticket transaction.

## Data and privacy

- Data read/written: bounded Micrometer metrics, log message/severity/timestamp, resource attributes, request/correlation MDC values, and sampled trace metadata.
- PII/secrets: raw comment/note/body, email, search query, password, token, cookie, Authorization value, audit ciphertext, actor/ticket identity, full URL, and unbounded exception values remain forbidden.
- Retention category: monitoring-server operator policy; this task creates no product retention table or audit retention change.
- Redaction/encryption: application logging policy is retained; OTLP log attributes are bounded to 16 attributes and 256 characters, and the collector uses private endpoint transport.
- Export/webhook exposure: no product export/webhook change; monitoring endpoints and collector endpoints are private only.

## Threats changed

- Authorization bypass: management port remains outside the frontend/public proxy and requires a private source allowlist.
- Impersonation: no new request actor is accepted; telemetry has no authority over server identity.
- Replay/duplicate: duplicated/lost telemetry is operationally tolerable and does not replay a domain command.
- SSRF/XSS: static private observability endpoints only; no user-supplied endpoint or log query is fetched by the backend.
- Secret leakage: no Docker socket/host log source and no credential in a generated configuration; endpoint URLs must not embed credentials.
- Audit bypass/tampering: telemetry is separate from immutable audit ledgers.
- Concurrency/data loss: no product data path changes; collector's ephemeral batch storage makes loss after collector restart explicit and non-audit.

## Acceptance scenarios

- Given the normal personal-staging compose set, when the overlay is omitted, then the backend has only `production` active and publishes no `9090`/`12345` port.
- Given the opt-in overlay and a private bind address, when Compose resolves it, then the backend retains `SPRING_PROFILES_ACTIVE=production`, adds only `personal-staging-observability`, and publishes `9090` only on that private address.
- Given the overlay, when the collector starts, then it has an internal OTLP receiver for backend logs/traces and no Docker socket, Docker discovery, host mount, host network/PID namespace, privilege, or profiler.
- Given a normal safe request with a bounded correlation ID, when private Loki/Tempo ingestion is configured, then Grafana can find the ID as structured metadata and follow its trace without making it a metric/log label.
- Given a missing collector or remote telemetry endpoint, when the backend serves a ticket command/read, then domain/audit semantics remain unchanged; the operator sees telemetry delivery failure separately.
- Given the private Prometheus targets, when either backend or Alloy is unavailable, then `up{environment="personal-staging",stack="deskseed"}` and the supplied target-down rule reveal it.

## Validation

- `make docs-check`
- `bash scripts/test-production-compose-contract.sh`
- `bash scripts/test-personal-staging-deploy.sh`
- `sh scripts/validate-observability-config.sh`
- `cd backend && ./gradlew --no-daemon fastTest --tests dev.deskseed.LoadObservabilityConfigurationTest --tests dev.deskseed.PersonalStagingObservabilityConfigurationTest`
- OPS-004 live verification remains: private source allowlist, Prometheus `up`, Loki OTLP ingestion, Tempo ingestion, and a bounded correlation drill
- ACC-007 remains a logging-policy regression gate; this task does not add a raw-content test corpus

## Compatibility and migration

- OpenAPI change classification: none.
- Migration/rollback: no product schema migration; deployment rollback removes the optional overlay and manually stops/removes its Alloy container.
- Backfill: none.
- Existing client/UI impact: none; the public frontend keeps the same surface.

## Human explanation

- Why this domain/transaction boundary: a monitoring collector cannot decide the result of a ticket or audit transaction.
- Why this permission and audit behavior: only the operator's private network can reach telemetry surfaces, while audit still uses its own required persistence.
- Why this is the simplest sufficient technology: Prometheus pull plus a backend-only OTLP collector gives metrics/logs/traces without host-wide Docker access or a second monitoring stack.
- What measured evidence would change the design: a multi-instance rollout, an approved host log-source boundary, measured need for host/database exporters, collector loss rate, or production monitoring policy.
