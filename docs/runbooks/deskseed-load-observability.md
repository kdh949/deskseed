# Deskseed load observability runbook

## Boundary

This runbook starts a disposable Linux load environment and connects it to an existing private monitoring server. It does not modify that server automatically. Do not use these commands against production volumes or a public interface.

Required versions are Prometheus 3.14, Loki 3.7, Tempo 2.10, Pyroscope 2.2, Grafana 13.2, Alloy 1.18.0, and k6 2.0.0. The validation host also needs Node.js and `jq`. The Pyroscope Java agent is pinned to 2.9.1 with SHA-256 `4b9aa1ba327a9ce2b1ce062578ad6b876c0d2496f15a00f0fdc4c953638cb76d`; ingest compatibility with the 2.2 server must pass before a load run is accepted.

## 1. Prepare private addresses and database monitoring

Copy `ops/observability/.env.observability.example` outside the repository, set the Deskseed VPN address and the private monitoring endpoints, and keep it mode `0600`. `DESKSEED_APP_BIND_ADDRESS` and `DESKSEED_OBSERVABILITY_BIND_ADDRESS` must never be `0.0.0.0` on the load host.

Create a fresh Compose project and database volume. In PostgreSQL, create a login used only by postgres-exporter and grant the built-in monitoring role:

```text
CREATE ROLE deskseed_monitor LOGIN;
\password deskseed_monitor
GRANT pg_monitor TO deskseed_monitor;
```

Create the external pgpass file referenced by `DESKSEED_POSTGRES_EXPORTER_PGPASS_FILE` with mode `0600`:

```text
db:5432:deskseed:deskseed_monitor:<operator-entered-password>
```

The load-only database enables `pg_stat_statements` and `track_io_timing`. Existing database volumes require the operator to run `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;` once after restart. The exporter enables its built-in statement collector without query text. Prometheus retains calls, execution seconds, rows, and block read/write seconds by the exporter's existing database/query identifiers. Mean execution time is derived as `rate(seconds_total) / rate(calls_total)` only when the call rate is positive. The collector's top 100 statements by cumulative execution time are a bounded sample, not a complete SQL census. No SQL text is collected.

## 2. Validate and start Deskseed

```bash
./scripts/validate-observability-config.sh

docker compose \
  --env-file /absolute/path/to/deskseed-load.env \
  -p deskseed-load \
  -f compose.yaml \
  -f compose.observability.yaml \
  --profile observability \
  up -d --build
```

Completion criteria:

- the public frontend returns `404` for `/actuator/prometheus`;
- the monitoring server can reach the VPN-bound `9090`, `9100`, `8081`, `9113`, `9187`, `9121`, and `12345` ports;
- no other source address can reach those ports;
- Alloy can reach the private Loki push and Tempo OTLP endpoints;
- the backend reports successful Pyroscope agent startup without retries or rejected uploads.

The Alloy Docker socket mount can expose Docker control if the collector is compromised even though the bind mount is read-only. It is accepted only on this disposable load host. Production adoption requires a separately reviewed file source or restricted socket proxy.

## 3. Apply monitoring-server files manually

1. Copy `deskseed-load.rules.yml` to the Prometheus rules directory.
2. Merge the seven scrape jobs from `prometheus.yml.example` into the existing Prometheus configuration and replace `deskseed-load.internal` with the load host VPN name/address. Keep the `host` relabel rule on every job; it extracts the common hostname from each target address. Use the same hostname for all seven targets on a load host. Remove the older metric drop rule for `pg_stat_statements` row/read/write counters.
3. Start Prometheus with `--web.enable-remote-write-receiver`, reachable only from the k6 host VPN address.
4. Ensure Tempo OTLP HTTP listens on the private interface rather than its loopback default.
5. Reload Prometheus before importing `grafana/deskseed-load-overview.json` and `grafana/deskseed-load-diagnostics.json`; replace the `prometheus`, `loki`, `tempo`, and `pyroscope` datasource UIDs if the server uses different UIDs. Version 3 dashboards require the new `host` label. Historical server series collected without it will not appear in these panels; use a window after the reload.
6. Merge the correlation fields from `grafana/datasource-correlations.yml.example` into the existing datasource provisioning. Preserve unrelated datasource settings. Tempo links to Loki by trace ID, Loki links back to Tempo from the safe `trace_id` field, and the diagnostics dashboard links the same service/environment/time range to Pyroscope.
7. Verify every `up{environment="load",stack="deskseed",host="<load-host>"}` series is `1`, including `deskseed-nginx`. Set Application host, Database, Hikari pool, and Compose project to match the deployment. Defaults for database and Compose project are `deskseed` and `deskseed-load`.

The backend must also be rebuilt/restarted with the updated load profile. It enables existing GC pause and Hikari acquire/usage histograms plus Tomcat's MBean registry; the product's production profile is unchanged. Histogram series increase scrape size, so compare scrape duration/sample count and preserve telemetry-overhead A/B evidence before accepting a capacity result.

The Pyroscope link is time-window correlation, not direct span-to-profile correlation. Direct span-profile linking remains deferred until the runtime emits the required profile correlation identifier.

No notification contact point is attached in this slice. Rules remain visible in Grafana/Prometheus and must be test-fired before production notification routing is considered.

## 4. Correlation and smoke acceptance

Run one synthetic request with valid `X-Request-Id` and `X-Correlation-Id` values. Confirm:

- Prometheus contains the normalized HTTP route and histogram without the IDs as labels;
- Loki finds the correlation ID as a JSON field and contains no body, query, email, cookie, token, or Authorization value;
- Tempo finds the trace under `service.name=deskseed-backend` without protected values;
- Pyroscope shows a CPU profile for `deskseed-backend{environment=load}` in the same time window.

Only after this succeeds may a k6 profile beyond one-VU smoke run.

After exercising synthetic HTTP, authentication limiter, and WebSocket paths and observing a GC cycle, run this read-only collection check from a host that can reach private Prometheus:

```bash
node scripts/load/verify-metrics.mjs http://monitoring.internal:9090 deskseed-load.internal
```

Use the actual `host` label as the second argument. Exit `0` means all checked targets and metric families are present; `1` means a target is down or a required series is missing; `2` means the check itself could not complete. `PRESENT` for a counter does not require a nonzero event count. GC and other event-created meters can be missing until exercised. This checks seven targets and representative measurement prerequisites, not every panel, Grafana rendering, event delivery, or capacity. Do not trigger a production GC or load merely to satisfy this check.

## 5. Read a measurement consistently

- Open the manifest's `correlation.dashboardLinks` on the Grafana origin to set the recorded run window, run ID, profile, and scenarios together. Then choose the application host explicitly. Server, DB, and host metrics describe all activity on that host during the window; they are not tagged with `test_run_id`. Do not overlap independent runs on the same host when attributing a bottleneck.
- The overview starts with arrived HTTP/completed iteration rate, client latency, errors, drops, and backend impact. It also exposes run-wide active/max VUs, checks, and target health. A business-flow iteration can make multiple requests; neither counter proves that the declared arrival rate was fully generated.
- k6 2.0.0 remote-write gauge percentiles use `k6_http_req_duration_p95/p99` in **seconds**, despite the absence of a `_seconds` suffix. The JSON summary and `*_MS` acceptance budgets still use milliseconds. They retain each operation/status series and describe that series' cumulative run distribution. Server histograms use **seconds** over `$__rate_interval`. Compare aligned operations and outcomes; subtracting two p95 values does not produce proxy/network latency. Run-wide VUs intentionally ignore the scenario filter.
- Diagnostics expose limiter latency/decisions, WebSocket outcomes, DB wait events/transaction age/statement I/O, Hikari hold/acquire/timeout, GC pause fraction, Tomcat threads, container CPU/throttling/memory, disk/network, and scrape health using existing meters/exporters. Wait-event counts are sampled sessions, not accumulated wait time. Container CPU is in cores; throttled-period ratio is not lost CPU time. An unbounded container memory setting can report host memory as its limit. Verify actual deployment limits before claiming headroom.
- `No data` means missing evidence. There is no synthetic healthy zero. `increase()` in the dropped-iteration stat is a window estimate and can miss increments before the first remote-write sample; use the final k6 summary for final counts. An absent dropped series does not independently establish zero drops.
- Read runner exit code, summary, and manifest together. A manifest `PASSED` only describes its recorded thresholds. Nonzero exit, missing/empty evidence, missing expected workload, or incomplete telemetry prevents accepting a run. A forcibly killed runner might leave an empty reserved file. No `k6_interrupted_iterations_total` metric is assumed.
- Logs, traces, and profiles currently correlate by service/environment/time, with correlation ID filtering for logs; they do not follow the Prometheus host selector. Check the source separately when multiple app hosts are active. Built-in dashboard annotations allow manual start/stop/deployment notes; the runner does not publish annotations.

For repeatable local query checks without Docker, install the pinned Prometheus 3.14.0 `promtool` and run:

```bash
node --test ops/observability/tests/*.test.mjs tests/load/tests/*.test.mjs
PROMTOOL=/absolute/path/to/promtool node scripts/load/check-dashboard-promql.mjs

K6_BIN=/absolute/path/to/k6 \
PROMETHEUS_BIN=/absolute/path/to/prometheus \
python3 scripts/load/check-k6-export.py
```

The query check parses every dashboard PromQL expression and evaluates synthetic cases for percentile identity, host isolation, and missing series. The optional Python 3 export check requires native k6 2.0.0 and Prometheus 3.14.0. It starts temporary loopback-only HTTP/Prometheus processes, imports the actual load option/tag code, verifies operation series and VU/check tags, and proves that a 250ms Time-valued Trend exports as 0.25 seconds. Its probe metric exists only in the disposable verification process. All temporary processes/data are removed when the check exits. This unit conversion also appears in the [pinned k6 Trend implementation](https://github.com/grafana/k6/blob/v2.0.0/internal/output/prometheusrw/remotewrite/trend.go#L50-L52) and its [adaptUnit function](https://github.com/grafana/k6/blob/v2.0.0/internal/output/prometheusrw/remotewrite/trend.go#L221-L231).

The full `validate-observability-config.sh` additionally requires Docker for Compose/Alloy/Prometheus/Nginx and pinned k6 inspection. Local verification evidence is recorded in `docs/evidence/load/2026-09-05-measurement-foundation.md`.

## Alert triage

### Target down

Check VPN reachability, host firewall, Compose service health, and the exact private bind address. Do not expose the port publicly to make the alert disappear.

### Audit write failure

Stop the load run. Inspect PostgreSQL health and the correlated application error. Required audit paths must not return success when persistence fails.

### HTTP errors

Separate expected `429` results from unexpected 4xx/5xx, then follow one failed request through Loki and Tempo.

### Database saturation

Compare Hikari pending/active, PostgreSQL connections/locks, container CPU, and `pg_stat_statements`. Do not raise the pool before confirming the database has spare capacity.

### Redis eviction

Stop AUTH-006 acceptance. The limiter requires bounded TTL with no eviction during the declared run.

### Disk low

Stop soak or profile capture and preserve evidence. Do not delete canonical application data as an alert action.

### Worker backlog

Compare incoming rate, `rate()` of the success/failure counters, oldest queued-work age, leases, and transport latency. The gauges are `deskseed_mail_outbox_oldest_age_seconds` and `deskseed_webhook_delivery_backlog_oldest_age_seconds`. A committed ticket mutation is not rolled back by delivery failure.

### Dropped iterations

The requested arrival rate was not generated. Treat the run as invalid and check load-generator CPU/network before interpreting Deskseed capacity.

## Rollback

```bash
docker compose \
  --env-file /absolute/path/to/deskseed-load.env \
  -p deskseed-load \
  -f compose.yaml \
  -f compose.observability.yaml \
  --profile observability \
  down
```

Remove the Deskseed scrape jobs, rules, dashboards, datasource correlation fields, and firewall allowances manually. Do not drop or erase a non-disposable volume. Removing the overlay does not change the product schema or OpenAPI contract.
