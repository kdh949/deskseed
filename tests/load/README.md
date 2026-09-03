# Deskseed k6 load suite

The suite targets only a disposable load deployment. The load generator must be separate from both the Deskseed host and monitoring server. Credentials and target URLs stay in an external mode-0600 env file.

Required common variables:

```text
TARGET_URL=https://deskseed-load.internal
TEST_RUN_ID=20260902-smoke-001
LOAD_PROFILE=smoke
K6_PROMETHEUS_RW_SERVER_URL=http://monitoring.internal:9090/api/v1/write
```

Every non-smoke env file must also declare reproducibility metadata. Use bounded identifiers/descriptions only; do not put credentials, URLs containing credentials, customer data, or tokens in these fields:

```text
LOAD_ENVIRONMENT_ID=load-host-shape-v1
LOAD_GENERATOR_ID=generator-host-shape-v1
APP_RESOURCE_LIMITS=backend=4cpu/8GiB,db=4cpu/8GiB
FIXTURE_DATASET_ID=synthetic-fixture-v1
FIXTURE_SIZE=tickets=100000,comments=500000
TELEMETRY_MODE=prometheus+loki+tempo+pyroscope
```

The runner records the checked-out commit SHA and whether the checkout is dirty. It never copies the full environment into an artifact.

Agent and WebSocket scenarios also require `STAFF_EMAIL`, `STAFF_PASSWORD`, and an optional `STAFF_VIEW_KEY` that has at least one fixture ticket. Public request additionally requires `CONFIRM_DESTRUCTIVE_WRITES=true`. Customer authentication requires a synthetic `CUSTOMER_EMAIL` and `CUSTOMER_PASSWORD`.

Run one-VU smoke first:

```bash
./scripts/load/run-k6.sh agent-read /absolute/path/to/load.env /absolute/path/to/results
```

Each run writes `<test-run-id>-<scenario>-summary.json` and `<test-run-id>-<scenario>-manifest.json` to the results directory. The manifest reports threshold pass/fail, the load/environment declaration, the safe dashboard selector, and the manual monitoring evidence that still needs to be preserved.

Any non-smoke run requires `CONFIRM_DESKSEED_LOAD_TARGET` equal to the exact `TARGET_URL` host. General arrival-rate profiles require `TARGET_RPS`; the customer auth script instead accepts `auth-sustained`, `auth-burst`, or `auth-safety`. The safety profile additionally requires `CONFIRM_AUTH_SAFETY=true`.

HTTP profiles must declare the environment-specific successful-response latency budgets. The authentication scenario applies these budgets to every expected `200`, `401`, or `429` outcome instead of treating the latter two as unexpected failures:

```text
MAX_HTTP_P95_MS=<measured acceptance budget>
MAX_HTTP_P99_MS=<measured acceptance budget, at least p95>
```

Non-smoke WebSocket profiles use separate connection budgets:

```text
MAX_WEBSOCKET_CONNECT_P95_MS=<measured acceptance budget>
MAX_WEBSOCKET_CONNECT_P99_MS=<measured acceptance budget, at least p95>
```

No repository-wide millisecond default is provided because the accepted budget belongs to the declared load environment. Missing or inverted budgets stop a non-smoke run during configuration.

`checks=100%`, `unexpected_status=0`, and `dropped_iterations=0` remain execution-validity gates. The declared p95/p99 thresholds are performance-acceptance gates, but they are not by themselves a capacity claim. Record the environment, fixture size, commit SHA, telemetry mode, JSON summary, Prometheus window, and `pg_stat_statements` snapshot before interpreting p95/p99 or bottlenecks.
