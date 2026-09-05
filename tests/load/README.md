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

The runner records the checked-out commit SHA and whether the checkout is dirty. This is the load-generator checkout, not proof of the deployed application revision; preserve the deployed image/revision separately. It never copies the full environment into an artifact. `TEST_RUN_ID` is required by the runner and must be unique for the scenario. Use up to 80 ASCII letters, digits, dots, underscores, or hyphens. Other characters are normalized before passing the ID to k6, and an existing result prefix is never overwritten.

Agent and WebSocket scenarios also require `STAFF_EMAIL`, `STAFF_PASSWORD`, and an optional `STAFF_VIEW_KEY` that has at least one fixture ticket. WebSocket authentication uses `DESKSEED_SESSION` by default; set `STAFF_SESSION_COOKIE_NAME` only when the deployment overrides that name. A successful upgrade alone does not pass the flow: the requested ticket's initial snapshot must arrive, and malformed/null messages fail the checks. Public request additionally requires `CONFIRM_DESTRUCTIVE_WRITES=true`. Customer authentication requires a synthetic `CUSTOMER_EMAIL` and `CUSTOMER_PASSWORD`.

Run one-VU smoke first:

```bash
./scripts/load/run-k6.sh agent-read /absolute/path/to/load.env /absolute/path/to/results
```

Each normally completed run writes `<test-run-id>-<scenario>-summary.json` and `<test-run-id>-<scenario>-manifest.json`. The runner separately records `<test-run-id>-<scenario>-runner.json` with start/end timestamps and the Docker/k6 exit code, including failed runs. Abrupt host failure or `SIGKILL` may leave that file empty and prevent summary/manifest creation; treat these as incomplete evidence.

Manifest schema version 2 reports threshold `PASSED`/`FAILED`, or `INCOMPLETE` when no thresholds were recorded. It includes the load/environment declaration, safe selector, and `correlation.dashboardLinks` with absolute `from`/`to` times and run/profile/scenario variables. Open those relative links on the Grafana origin and select the application host explicitly. The manifest window is recorded from script initialization through summary creation; the runner window also includes process/container startup and shutdown. Neither covers the subsequent recovery interval automatically. Extend the window deliberately when recording recovery.

Accept a run only after reviewing all three artifacts: runner exit code `0`, complete expected workload, passing thresholds, and the required monitoring evidence. A passing partial summary is not sufficient. A missing dropped-iteration series is unknown until checked against the final summary and executor outcome. The summary's exact counters take precedence over Prometheus `rate()`/`increase()` estimates. The runner explicitly selects Trend-as-Gauges for these dashboards. Those gauges retain cumulative per-operation percentiles in seconds; they cannot be aggregated into a scenario p95. k6 summary values and `*_MS` threshold inputs remain milliseconds.

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

For a simultaneous product mix, use `mixed-workload`. `MIXED_*_RPS` is business-flow iterations per second, not raw HTTP requests; one agent-read or public-request iteration performs several HTTP calls. Declare every rate, connection count, and per-flow latency budget explicitly:

```text
LOAD_PROFILE=mixed
MIXED_AGENT_RPS=<agent-read iterations/second>
MIXED_PUBLIC_RPS=<public-request iterations/second>
MIXED_AUTH_RPS=<authentication attempts/second>
MIXED_WEBSOCKET_CONNECTIONS=<concurrent collaboration sockets>
MIXED_AGENT_MAX_HTTP_P95_MS=<budget>
MIXED_AGENT_MAX_HTTP_P99_MS=<budget>
MIXED_PUBLIC_MAX_HTTP_P95_MS=<budget>
MIXED_PUBLIC_MAX_HTTP_P99_MS=<budget>
MIXED_AUTH_MAX_HTTP_P95_MS=<budget>
MIXED_AUTH_MAX_HTTP_P99_MS=<budget>
MIXED_WEBSOCKET_MAX_CONNECT_P95_MS=<budget>
MIXED_WEBSOCKET_MAX_CONNECT_P99_MS=<budget>
```

The mixed workload creates synthetic public requests, so it also requires `CONFIRM_DESTRUCTIVE_WRITES=true`. All four flows share `TEST_RUN_ID` but retain separate `scenario` tags and threshold selectors.

```bash
./scripts/load/run-k6.sh mixed-workload /absolute/path/to/load.env /absolute/path/to/results
```

`checks=100%`, `unexpected_status=0`, and `dropped_iterations=0` remain execution-validity gates. The declared p95/p99 thresholds are performance-acceptance gates, but they are not by themselves a capacity claim. Record the environment, fixture size, commit SHA, telemetry mode, JSON summary, Prometheus window, and `pg_stat_statements` snapshot before interpreting p95/p99 or bottlenecks.
