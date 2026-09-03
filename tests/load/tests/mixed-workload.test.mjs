import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const repositoryRoot = new URL("../../../", import.meta.url);

test("mixed workload runs all four product flows as separately tagged executors", async () => {
  const scenario = await readFile(new URL("tests/load/scenarios/mixed-workload.js", repositoryRoot), "utf8");

  assert.match(scenario, /agentReadFlow/);
  assert.match(scenario, /publicRequestFlow/);
  assert.match(scenario, /customerAuthLimiterFlow/);
  assert.match(scenario, /collaborationWebSocketFlow/);
  assert.match(scenario, /requireConfirmedTarget\(\{ writes: true \}\)/);
  assert.match(scenario, /handleSummaryFor\('mixed-workload'/);
});

test("mixed workload requires explicit rates, connections, and per-flow latency budgets", async () => {
  const config = await readFile(new URL("tests/load/lib/config.js", repositoryRoot), "utf8");

  for (const name of [
    "MIXED_AGENT_RPS",
    "MIXED_PUBLIC_RPS",
    "MIXED_AUTH_RPS",
    "MIXED_WEBSOCKET_CONNECTIONS",
    "MIXED_AGENT_MAX_HTTP_P95_MS",
    "MIXED_AGENT_MAX_HTTP_P99_MS",
    "MIXED_PUBLIC_MAX_HTTP_P95_MS",
    "MIXED_PUBLIC_MAX_HTTP_P99_MS",
    "MIXED_AUTH_MAX_HTTP_P95_MS",
    "MIXED_AUTH_MAX_HTTP_P99_MS",
  ]) {
    assert.match(config, new RegExp(name));
  }
  assert.match(config, /scenario:agent-read/);
  assert.match(config, /scenario:public-request/);
  assert.match(config, /scenario:customer-auth-limiter/);
  assert.match(config, /ws_connecting\{scenario:collaboration-websocket,name:collaboration_websocket\}/);
});

test("runner and validation include mixed-workload", async () => {
  const runner = await readFile(new URL("scripts/load/run-k6.sh", repositoryRoot), "utf8");
  const validation = await readFile(new URL("scripts/validate-observability-config.sh", repositoryRoot), "utf8");

  assert.match(runner, /mixed-workload/);
  assert.match(validation, /mixed-workload/);
});
