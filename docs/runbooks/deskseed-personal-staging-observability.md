# Personal-staging private observability runbook

This runbook enables detailed personal-staging diagnostics without changing the `production` Spring profile or exposing a public management endpoint. It is separate from the disposable `load` overlay.

## What this enables

```text
private Prometheus ──scrape──> backend :9090
private Prometheus ──scrape──> Alloy :12345

backend (production + personal-staging-observability)
  ├─ Prometheus metrics (private :9090)
  └─ OTLP logs/traces ──internal Compose network──> Alloy
                                                ├─ Loki native OTLP /otlp
                                                └─ Tempo OTLP HTTP
```

Only the backend sends OTLP to Alloy. The collector does not mount `/var/run/docker.sock`, discover Docker containers, read host files, run a profiler, or receive a host-published OTLP port.

## 1. Prepare the monitoring server manually

Do not overwrite an existing shared Prometheus/Loki/Tempo configuration.

1. Merge the two jobs from [prometheus.yml.example](../../ops/observability/personal-staging/prometheus.yml.example) into the existing Prometheus configuration. Replace `deskseed-personal-staging.internal` with the private VPN/DNS name of the personal-staging host.
2. Copy [deskseed-personal-staging.rules.yml](../../ops/observability/personal-staging/deskseed-personal-staging.rules.yml) into the Prometheus rules directory and update the local `rule_files` path if necessary.
3. Confirm Loki is version 3.0 or newer and has native OTLP structured metadata enabled:

   ```yaml
   limits_config:
     allow_structured_metadata: true
   ```

   Expose the Loki OTLP endpoint as a private URL ending in `/otlp`. Do not put a username, password, API key, or token in the URL.
4. Confirm Tempo's OTLP HTTP endpoint is private and reachable from the staging host.
5. Import [deskseed-personal-staging-overview.json](../../ops/observability/personal-staging/grafana/deskseed-personal-staging-overview.json), mapping its `prometheus`, `loki`, and `tempo` data-source UIDs if your Grafana instance uses different IDs.

This repository does not reload or otherwise mutate the monitoring server for you.

## 2. Set private deployment values

In the existing mode-0600 `/etc/deskseed/production.env`, set only non-secret endpoint values such as:

```dotenv
DESKSEED_OBSERVABILITY_BIND_ADDRESS=10.20.30.40
DESKSEED_LOKI_OTLP_HTTP_ENDPOINT=https://loki.monitoring.internal/otlp
DESKSEED_TEMPO_OTLP_HTTP_ENDPOINT=https://tempo.monitoring.internal
DESKSEED_PERSONAL_STAGING_TRACE_SAMPLING_PROBABILITY=0.05
```

`DESKSEED_OBSERVABILITY_BIND_ADDRESS` must be the host's private/VPN address, never `0.0.0.0`. Permit inbound `9090` and `12345` only from the monitoring-server address. Do not publish `4317` or `4318`. If the monitoring system requires authentication, use its private reverse-proxy/mTLS policy rather than embedding credentials in a URL or container environment.

## 3. Deploy the opt-in overlay

First use the same commit SHA whose backend/frontend images were published. The normal deployer still verifies the checkout SHA, OCI revision labels, migrations, runtime roles, and frontend health.

```bash
DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED=true \
  ./scripts/deploy-personal-server.sh <40-character-published-sha>
```

The successful application deployment ends with `Personal staging deployment passed for <sha>.` When the overlay is enabled, the deployer additionally requires the Alloy container to be running. It does not prove that Loki, Tempo, or Prometheus received data.

## 4. Verify the three signals

Wait at least one Prometheus scrape interval, then run these from Grafana/Prometheus:

```promql
up{environment="personal-staging",stack="deskseed"}
```

Both `deskseed-backend` and `deskseed-alloy` should be `1`. The dashboard then shows request rate, 5xx, route p95/p99, JVM heap/CPU, Hikari state, required-audit failures, and the Loki log panel.

For a correlation drill, make a normal, non-sensitive authenticated read using a bounded `X-Correlation-Id`, then query Loki:

```logql
{service_name="deskseed-backend", deployment_environment_name="personal-staging"}
| correlationId = "your-bounded-correlation-id"
```

`correlationId`, request ID, severity, and trace context are structured metadata, not labels. Open the trace ID exposed by the selected log in Tempo. Never paste body text, a cookie, a token, Authorization value, email address, search query, or full signed URL into Grafana Explore.

If Prometheus shows `up=1` but logs/traces are absent, inspect the collector without printing environment values:

```bash
docker compose --project-name deskseed \
  --env-file /etc/deskseed/production.env \
  --file compose.yaml \
  --file compose.production.yaml \
  --file compose.personal-staging.yaml \
  --file compose.personal-staging-observability.yaml \
  logs --tail 100 alloy
```

Classify the result as `Passed` only after the Prometheus scrape, one safe log, and its matching trace are all visible. A running container, an HTTP 200, or a Grafana `No data` panel is not ingest evidence.

## 5. Roll back

Redeploy without the opt-in flag to remove the additional Spring profile from the backend. The optional Alloy service is intentionally not removed as an implicit Compose orphan; stop and remove that exact service explicitly after the application rollback is healthy:

```bash
IMAGE_TAG=<currently-deployed-sha> docker compose --project-name deskseed \
  --env-file /etc/deskseed/production.env \
  --file compose.yaml \
  --file compose.production.yaml \
  --file compose.personal-staging.yaml \
  --file compose.personal-staging-observability.yaml \
  stop alloy

IMAGE_TAG=<currently-deployed-sha> docker compose --project-name deskseed \
  --env-file /etc/deskseed/production.env \
  --file compose.yaml \
  --file compose.production.yaml \
  --file compose.personal-staging.yaml \
  --file compose.personal-staging-observability.yaml \
  rm --force alloy
```

Then remove the two Prometheus targets, rule fragment, private firewall allows, and Grafana dashboard if they are no longer wanted. No database or public API rollback is involved.

## Failure guides

### Target down

- Confirm the private bind address is assigned to the host and the monitoring-server source allowlist permits only `9090`/`12345`.
- Check `docker compose ... ps --all` for backend and Alloy before changing application credentials or database settings.
- Do not change the public frontend proxy to make `/actuator/prometheus` reachable.

### Audit write failure

- Treat `deskseed_audit_persistence_failures_total` as a fail-closed application condition, not an observability-only warning.
- Investigate database role/capacity and preserve incident evidence. Do not recover it by weakening required audit persistence or logging protected content.

### HTTP errors

- Start with the dashboard's route latency, 5xx ratio, and JVM panels; then use one safe correlation ID in Loki and Tempo.
- This overlay is not a load-test result. Do not infer capacity, SLA, or a production bottleneck without a separately authorized versioned workload and supporting evidence.

### Database saturation

- Start with the dashboard's Hikari pending/active/idle panels, then use one safe correlation ID in Loki and Tempo.
- This overlay is not a load-test result. Do not infer capacity, SLA, or a production bottleneck without a separately authorized versioned workload and supporting evidence.
