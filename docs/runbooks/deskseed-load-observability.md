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

The load-only database enables `pg_stat_statements` and `track_io_timing`. Existing database volumes require the operator to run `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;` once after restart. The exporter enables its built-in statement collector without query text. Prometheus retains only `queryid`, calls, and total execution seconds; mean execution time is derived as `rate(seconds_total) / rate(calls_total)`.

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
2. Merge the seven scrape jobs from `prometheus.yml.example` into the existing Prometheus configuration and replace `deskseed-load.internal` with the load host VPN name/address.
3. Start Prometheus with `--web.enable-remote-write-receiver`, reachable only from the k6 host VPN address.
4. Ensure Tempo OTLP HTTP listens on the private interface rather than its loopback default.
5. Import `grafana/deskseed-load-overview.json`; replace the `prometheus`, `loki`, `tempo`, and `pyroscope` datasource UIDs if the server uses different UIDs.
6. Reload Prometheus and verify every `up{environment="load",stack="deskseed"}` series is `1`, including `deskseed-nginx`.

No notification contact point is attached in this slice. Rules remain visible in Grafana/Prometheus and must be test-fired before production notification routing is considered.

## 4. Correlation and smoke acceptance

Run one synthetic request with valid `X-Request-Id` and `X-Correlation-Id` values. Confirm:

- Prometheus contains the normalized HTTP route and histogram without the IDs as labels;
- Loki finds the correlation ID as a JSON field and contains no body, query, email, cookie, token, or Authorization value;
- Tempo finds the trace under `service.name=deskseed-backend` without protected values;
- Pyroscope shows a CPU profile for `deskseed-backend{environment=load}` in the same time window.

Only after this succeeds may a k6 profile beyond one-VU smoke run.

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

Remove the Deskseed scrape jobs, rules, dashboard, and firewall allowances manually. Do not drop or erase a non-disposable volume. Removing the overlay does not change the product schema or OpenAPI contract.
